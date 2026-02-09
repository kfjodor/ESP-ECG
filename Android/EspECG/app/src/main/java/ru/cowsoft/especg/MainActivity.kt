package ru.cowsoft.especg

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.cowsoft.especg.ui.theme.EspECGTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class MainActivity : ComponentActivity() {

    private val SERVICE_UUID = UUID.fromString("0000DEAD-0000-1000-8000-00805F9B34FB")
    private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val CHARACTERISTIC_UUID = UUID.fromString("0000BEEF-0000-1000-8000-00805F9B34FB")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val SAMPLING_RATE = 500

    private val ecgData = mutableStateListOf<Float>()
    private var writeIndex by mutableIntStateOf(0)
    private var horizontalScale by mutableFloatStateOf(1000f)

    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var isScanning by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private var isPaused by mutableStateOf(false)
    private var packetsReceived by mutableStateOf(0)
    private var lastRawData by mutableStateOf("...")
    private var bluetoothGatt: BluetoothGatt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            EspECGTheme {
                var showDialog by remember { mutableStateOf(false) }
                var statusText by remember { mutableStateOf("Готов") }
                var hasPermissions by remember { mutableStateOf(false) }

                val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { perms ->
                    hasPermissions = perms.values.all { it }
                }

                LaunchedEffect(Unit) {
                    hasPermissions = requiredPermissions.all {
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            it
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                    if (!hasPermissions) permissionLauncher.launch(requiredPermissions)
                    resetBuffer()
                }

                val bluetoothManager =
                    getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        Text(
                            "EspECG Sweep Monitor",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp)
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Статус: $statusText",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "Пакетов: $packetsReceived",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Text(
                                    lastRawData,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        ECGChart(
                            data = ecgData,
                            currentIndex = writeIndex,
                            maxPoints = horizontalScale.toInt(),
                            samplingRate = SAMPLING_RATE,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(350.dp)
                                .padding(16.dp)
                                .background(Color(0xFF000800))
                        )

                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Обзор: ${(horizontalScale / SAMPLING_RATE).format(1)} сек",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = horizontalScale,
                                onValueChange = { horizontalScale = it; resetBuffer() },
                                valueRange = 250f..2500f
                            )
                        }

                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                        ) {
                            if (!isConnected) {
                                Button(onClick = {
                                    if (hasPermissions) {
                                        disconnect()
                                        statusText = "Поиск..."
                                        startBleScan(adapter)
                                        showDialog = true
                                    } else {
                                        permissionLauncher.launch(requiredPermissions)
                                    }
                                }, modifier = Modifier.weight(1f)) {
                                    Text("Поиск")
                                }
                            } else {
                                Button(onClick = {
                                    isPaused = !isPaused
                                }, modifier = Modifier.weight(1f)) {
                                    Text(if (isPaused) "Продолжить" else "Пауза")
                                }
                            }
                                /*
                            Spacer(Modifier.width(8.dp))
                            
                            Button(
                                onClick = { 
                                    disconnect()
                                    statusText = "Остановлено" 
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                modifier = Modifier.weight(0.5f)
                            ) {
                                Text("Стоп")
                            } */
                        }
                    }

                    if (showDialog) {
                        DeviceSelectionDialog(
                            isScanning = isScanning,
                            devices = discoveredDevices,
                            onDismiss = { stopBleScan(adapter); showDialog = false },
                            onDeviceSelected = { device ->
                                stopBleScan(adapter)
                                showDialog = false
                                statusText = "Связь с ${getDeviceDisplayName(device)}..."
                                connectToDevice(device) { ok ->
                                    statusText = if (ok) "Подключено ✅" else "Ошибка подключения ❌"
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    private fun resetBuffer() {
        val size = horizontalScale.toInt()
        if (ecgData.size != size) {
            ecgData.clear()
            repeat(size) { ecgData.add(0f) }
            writeIndex = 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
            Log.d("BLE", "GATT closed and disconnected")
        }
        bluetoothGatt = null
        packetsReceived = 0
        isConnected = false
        isPaused = false
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceDisplayName(device: BluetoothDevice): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            device.alias ?: device.name ?: "Unknown Device"
        } else {
            device.name ?: "Unknown Device"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(adapter: BluetoothAdapter?) {
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }
        discoveredDevices.clear()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "Ошибка сканера BLE", Toast.LENGTH_SHORT).show()
            return
        }

        isScanning = true
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID)).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        Log.d("BLE_SCAN", "Scan started with filters")

        android.os.Handler(mainLooper).postDelayed({ stopBleScan(adapter) }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan(adapter: BluetoothAdapter?) {
        if (isScanning) {
            isScanning = false
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d("BLE_SCAN", "Scan stopped")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = getDeviceDisplayName(device)
            if (discoveredDevices.none { it.address == device.address }) {
                Log.d("BLE_SCAN", "Found target: $name [${device.address}]")
                discoveredDevices.add(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE_SCAN", "Scan failed with error: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, onResult: (Boolean) -> Unit) {
        bluetoothGatt?.close()
        resetBuffer()

        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLE", "Connected, requesting MTU")
                    gatt.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BLE", "Disconnected")
                    runOnUiThread { 
                        isConnected = false
                        onResult(false) 
                    }
                    gatt.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d("BLE", "MTU changed to $mtu, discovering services")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID) ?: gatt.getService(HEART_RATE_SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(
                                    descriptor,
                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                            Log.d("BLE", "Notifications enabled")
                            runOnUiThread { 
                                isConnected = true
                                onResult(true) 
                            }
                        }
                    } else {
                        Log.e("BLE", "Target characteristic not found")
                        runOnUiThread { onResult(false) }
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                processData(value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                processData(characteristic.value)
            }

            private fun processData(bytes: ByteArray?) {
                if (bytes == null || isPaused) return
                val shortBuffer =
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val points = mutableListOf<Float>()
                while (shortBuffer.hasRemaining()) points.add(shortBuffer.get().toFloat())

                runOnUiThread {
                    packetsReceived++
                    lastRawData = "HEX: " + bytes.joinToString("") { "%02X".format(it) }.take(16)

                    val currentMax = horizontalScale.toInt()
                    if (ecgData.size != currentMax) resetBuffer()

                    points.forEach { p ->
                        val pos = writeIndex % currentMax
                        if (pos < ecgData.size) ecgData[pos] = p
                        writeIndex++
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun DeviceSelectionDialog(
        isScanning: Boolean,
        devices: List<BluetoothDevice>,
        onDismiss: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isScanning) "Поиск устройств..." else "Выберите устройство") },
            text = {
                Column {
                    if (isScanning) LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                    if (devices.isEmpty() && !isScanning) Text("Устройства не найдены. Проверьте питание устройства и GPS.")
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(devices) { device ->
                            ListItem(
                                headlineContent = { Text(getDeviceDisplayName(device)) },
                                supportingContent = { Text(device.address) },
                                modifier = Modifier.clickable { onDeviceSelected(device) }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
        )
    }
}

@Composable
fun ECGChart(
    data: List<Float>,
    currentIndex: Int,
    maxPoints: Int,
    samplingRate: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stepX = size.width / (maxPoints.coerceAtLeast(2) - 1)

        val pts10ms = samplingRate / 100
        val pts50ms = pts10ms * 5
        val pts200ms = pts50ms * 4

        for (i in 0 until maxPoints step pts10ms) {
            val x = i * stepX
            val color = when {
                i % pts200ms == 0 -> Color(0xFF006600)
                i % pts50ms == 0 -> Color(0xFF003300)
                else -> Color(0xFF001500)
            }
            drawLine(
                color,
                Offset(x, 0f),
                Offset(x, size.height),
                if (i % pts200ms == 0) 2f else 1f
            )
        }

        if (data.size < maxPoints || maxPoints <= 0) return@Canvas

        val minVal = data.minOrNull() ?: 0f
        val maxVal = data.maxOrNull() ?: 4095f
        val range = (maxVal - minVal).coerceAtLeast(300f)
        val currentPos = currentIndex % maxPoints
        val gap = (maxPoints / 20).coerceAtLeast(15)

        fun getY(v: Float) =
            size.height - (((v - minVal) / range) * size.height * 0.8f + size.height * 0.1f)

        val pathNew = Path()
        for (i in 0 until currentPos) {
            val x = i * stepX
            val y = getY(data[i])
            if (i == 0) pathNew.moveTo(x, y) else pathNew.lineTo(x, y)
        }
        drawPath(pathNew, Color(0xFF00FF44), style = Stroke(width = 3.dp.toPx()))

        if (currentPos + gap < maxPoints) {
            val pathOld = Path()
            var first = true
            for (i in (currentPos + gap) until maxPoints) {
                val x = i * stepX
                val y = getY(data[i])
                if (first) {
                    pathOld.moveTo(x, y); first = false
                } else pathOld.lineTo(x, y)
            }
            drawPath(
                pathOld,
                Color(0xFF00FF44).copy(alpha = 0.1f),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        if (currentPos > 0) {
            drawCircle(
                Color.White,
                radius = 6f,
                center = Offset(currentPos * stepX, getY(data[currentPos - 1]))
            )
        }
    }
}
