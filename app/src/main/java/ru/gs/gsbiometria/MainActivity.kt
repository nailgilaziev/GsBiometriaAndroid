package ru.gs.gsbiometria

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.mattprecious.swirl.SwirlView
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.util.*

data class User (val id: Int, val name: String, val lastname: String,val comment: String)

object FingerData {
    val users = mutableListOf<User>()
    private lateinit var file: File
    fun nextId() =
            if(users.isEmpty()) 0
            else users.last().id + 1

    fun readData(){
        val sdcard = Environment.getExternalStorageDirectory()
        file = File(sdcard,"gsbiometria.txt")
        file.createNewFile()
        val text = FileInputStream(file).bufferedReader().use { it.readText() }
        users .addAll(text.split("\n").filter { it.isNotBlank() }.map {
            val t = it.split(";")
            User(t[0].toInt(),t[1],t[2],t[3])
        })
    }

    fun writeData(user: User){
        users.add(user)
        file.appendText(user.run { "${if (id==0) "" else "\n"}$id;$name;$lastname;$comment" })
    }

}


class MainActivity : Activity() {

    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    val RECIEVE_MESSAGE = 1        // Статус для Handler

    private var btThread: ConnectivityThread? = null

    var s = ""
    private val h: Handler = object : Handler() {
        override fun handleMessage(msg: android.os.Message) {
            when (msg.what) {
                RECIEVE_MESSAGE                                                   // если приняли сообщение в Handler
                -> {
                    val readBuf = msg.obj as ByteArray
                    val strIncom = String(readBuf, 0, msg.arg1)
                    println("str incom $strIncom")
                    s += strIncom
//                        sb.append(strIncom)                                                // формируем строку
                    val endOfLineIndex = s.indexOf("\r\n")                            // определяем символы конца строки
                    if (endOfLineIndex > 0) {                                            // если встречаем конец строки,
                        println("full line readed\n$s")
                        s=s.trim()
                        if (s=="f1e") enterFinger()
                        if (s=="f1r") firstFingerEntered()
                        if (s=="f2e") enterSecondTime()
                        if (s=="f2r") afterEnteringAll()
                        if (s=="fe") threadStoppedUi()
                        s=""
                    }
                }
            }//Log.d(TAG, "...Строка:"+ sb.toString() +  "Байт:" + msg.arg1 + "...");
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(R.layout.activity_main)

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Should we show an explanation?
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
                Toast.makeText(this, "Permission needed", Toast.LENGTH_LONG).show()
                finish()
                return
            } else {
                // No explanation needed, we can request the permission.
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), MY_PERMISSIONS_REQUEST)
                // MY_PERMISSIONS_REQUEST is an
                // app-defined int constant. The callback method gets the
                // result of the request.
                return
            }
        }
        initialize()
    }

    private fun initialize() {
        val state = Environment.getExternalStorageState()
        if (state != Environment.MEDIA_MOUNTED) {
            Toast.makeText(this, "External storage not available", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            FingerData.readData()
        } catch (e: Exception) {
            Log.e("FILE", e.toString())
            Toast.makeText(this, "Problem with file", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(btInfoReceiver, intentFilter)

        button.setOnClickListener {
            if (button.text.toString().equals("ДАЛЕЕ", true)) {
                if (enteredDataIsValid()) afterEnteringData()
            } else {
                initializeUI()
            }
            //if (button.text.toString().equals("Добавить новый",true))
        }

        initializeUI()
    }

    fun initializeUI() {
        button.text = "ДАЛЕЕ"
        button.off()
        swirl.setState(SwirlView.State.OFF)
        swirl.off()
        label.text = "Ожидание подключения к устройству"
        progress.on()
        simulate { bt() }
    }

    val REQUEST_ENABLE_BT = 1
    val MY_PERMISSIONS_REQUEST = 2


    val GS_BIOMETRIA_MAC = "20:16:12:01:11:33"


    val btInfoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val t = "RECEIVER"
                when (it.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> Log.d(t, "ACTION_DISCOVERY_STARTED")
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> Log.d(t, "ACTION_DISCOVERY_FINISHED")
                    BluetoothDevice.ACTION_ACL_CONNECTED -> Log.d(t, "ACTION_ACL_CONNECTED")
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        Log.d(t, "ACTION_ACL_DISCONNECTED")
                        btThread?.interrupt()
                    }
                    else->Log.w(t, "RECEIVED ANOTHER EVENT")
                }
            }
        }
    }

    val btAdapter = BluetoothAdapter.getDefaultAdapter()

    fun bt() {
        if (btAdapter == null) {
            Toast.makeText(this, "Device does not support Bluetooth", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!btAdapter.isEnabled()) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        val devices = btAdapter.bondedDevices
        for (device in devices) {
            if (device.address == GS_BIOMETRIA_MAC) {
                if (btThread?.isAlive == true){
                    afterConnecting()
                    return
                }
                btAdapter.startDiscovery()
                Log.d("THREAD", "Instance created")
                btThread = ConnectivityThread(device)
                btThread!!.start()
                return
            }
        }
        Toast.makeText(this, "Pair your device with GSBiometria before use the app", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_BT) {
            simulate { bt() }
//            if (resultCode == RESULT_OK)
//            else simulate { bt() }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>?, grantResults: IntArray?) {
        if (requestCode == MY_PERMISSIONS_REQUEST){
            if (grantResults!= null && grantResults.size > 0 && grantResults.first() == PackageManager.PERMISSION_GRANTED){
                initialize()
            } else {
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }


    fun afterConnecting() {
        progress.off()
        label.text = "Новый отпечаток"
        name.on()
        lastname.on()
        comment.on()
        button.on()
        name.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT)
    }

    fun enteredDataIsValid(): Boolean {
        var v = true
        if (name.text.toString().isBlank()) {
            name.error = "Нужно заполнить"
            v = false
        }
        if (lastname.text.toString().isBlank()) {
            lastname.error = "Нужно заполнить"
            v = false
        }
        return v
    }

    fun afterEnteringData() {
        currentFocus?.let {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
        label.text = "Обмен данными"
        name.off()
        lastname.off()
        comment.off()
        button.off()
        progress.on()
        btThread!!.write("1${FingerData.nextId()}\n")
    }

    fun enterFinger(){
        progress.off()
        label.text = "${name.text} ${lastname.text}"
        actionlabel.text = "Приложите палец к устройству"
        actionlabel.on()
        swirl.on()
        swirl.setState(SwirlView.State.ON)
    }

    fun firstFingerEntered() {
        actionlabel.text = "Уберите палец"
        swirl.setState(SwirlView.State.OFF)
    }

    fun enterSecondTime() {
        actionlabel.text = "Еще раз, приложите палец повторно"
        swirl.setState(SwirlView.State.ON)
    }

    fun afterEnteringAll() {
        label.text = "Успешно, пробуйте!"
        actionlabel.off()
//        actionlabel.text = ""
        swirl.setState(SwirlView.State.ON)
        simulate {
            swirl.setState(SwirlView.State.ON)
        }
        FingerData.writeData(User(FingerData.nextId(), name.text.toString(),lastname.text.toString(),comment.text.toString()))
        name.setText("")
        lastname.setText("")
        comment.setText("")
        button.on()
        button.text = "Добавить новый"

    }

    private fun threadStoppedUi() {
        progress.off()
        name.off()
        lastname.off()
        comment.off()
        button.on()
        swirl.on()
        actionlabel.off()
        label.text = "Связь с устройством потеряна"
        button.text = "ВОЗОБНОВИТЬ"
        swirl.setState(SwirlView.State.ERROR)
        btThread!!.interrupt()
    }

    fun View.on() {
        visibility = View.VISIBLE
    }

    fun View.off() {
        visibility = View.GONE
    }

    fun simulate(f: () -> Unit) {
        Handler().postDelayed(f, 1000)
    }

    override fun onPause() {
        super.onPause()
        try {
            btThread?.interrupt()
        } catch (e2: IOException) {
            println("Fatal Error In onPause() and failed to close socket." + e2.message + ".")
        }
    }

    override fun onResume() {
        super.onResume()
        if (btThread != null && !btThread!!.isAlive) {
            Log.d("THREAD", "Now can start THREAD")
            initializeUI()
        }
    }

    private inner class ConnectivityThread(private val mmDevice: BluetoothDevice) : Thread() {

        private lateinit var btSocket: BluetoothSocket
        private lateinit var inStream: InputStream
        private lateinit var outStream: OutputStream

        val tag = "CONNECTIVITY THREAD"

        override fun run() {
            try {
                Log.d(tag, "Thread runned")
                Log.d(tag, "cancel discovery")
                btAdapter.cancelDiscovery()
                var connecting = true
                while (connecting && !interrupted()) {
                    Log.d(tag, "attempt to connect")
                    try {
                        sleep(5 * 1000)
                    } catch (e: InterruptedException) {
                        Log.d(tag, "Connecting process interrupted")
                        throw e
                    }
                    try {
                        btSocket = mmDevice.createRfcommSocketToServiceRecord(MY_UUID)
                        Log.d(tag, "socket created")
                    } catch (ex: IOException) {
                        Log.w(tag, "Exception during create socket $ex")
                        throw ex
                    }
                    try {
                        btSocket.connect()
                        Log.d(tag, "socket connected")
                    } catch (ex: IOException) {
                        Log.w(tag, "Exeption when opening socket $ex")
                        try {
                            btSocket.close()
                        } catch (ignoreEx: IOException) {
                            Log.e(tag, "fatal exeption when closing socket $ignoreEx")
                        }
                        continue
                    }
                    try {
                        inStream = btSocket.inputStream
                        outStream = btSocket.outputStream
                        Log.d(tag, "streams readed")
                    } catch (e: IOException) {
                        Log.e(tag, "Input Output stream getting exception $e")
                        throw e
                    }
                    connecting = false
                }

                runOnUiThread {
                    afterConnecting()
                }

                val buffer = ByteArray(256)  // buffer store for the stream
                var bytes: Int // bytes returned from read()

                // Keep listening to the InputStream until an exception occurs
                while (true) {
                    Log.d(tag, "attempt to read")
                    try {
                        sleep(1 * 1000)
                    } catch (e: InterruptedException) {
                        Log.d(tag, "Read process interrupted")
                        throw e
                    }
                    try {
                        // Read from the InputStream
                        if (inStream.available() != 0) {
                            bytes = inStream.read(buffer)
                            Log.d(tag, "readedBytes=$bytes")
                            if (bytes != -1) {
                                h.obtainMessage(RECIEVE_MESSAGE, bytes, -1, buffer).sendToTarget()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Exception when reading to buffer $e")
                        throw e
                    }
                }
            } catch (ignoreEx: InterruptedException) {
            } catch (ex: Throwable) {
                Log.e(tag, "Exception that stops the thread\n$ex")
            } finally {
                Log.d(tag, "thread cancel his live")
                try {
                    btSocket.close()
                } catch (ignoreEx: Exception) {
                    Log.e(tag, "fatal exeption when closing socket during finishisg thread $ignoreEx")
                }
            }
            runOnUiThread {
                threadStoppedUi()
            }
        }

        /* Call this from the main activity to send data to the remote device */
        fun write(message: String) {
            val msgBuffer = message.toByteArray()
            try {
                outStream.write(msgBuffer)
                Log.d(tag, "message writed $message")
            } catch (e: IOException) {
                println("...Ошибка отправки данных: " + e.message + "...")
                interrupt()
            }
        }
    }

}
