package com.siliconlabs.bledemo.Base

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import com.siliconlabs.bledemo.Adapters.DeviceInfoViewHolder
import com.siliconlabs.bledemo.Adapters.ScannedDevicesAdapter
import com.siliconlabs.bledemo.Bluetooth.BLE.BluetoothDeviceInfo
import com.siliconlabs.bledemo.Bluetooth.BLE.Discovery
import com.siliconlabs.bledemo.Bluetooth.BLE.*
import com.siliconlabs.bledemo.Bluetooth.BLE.Discovery.BluetoothDiscoveryHost
import com.siliconlabs.bledemo.Bluetooth.BLE.GattService
import com.siliconlabs.bledemo.Bluetooth.BLE.TimeoutGattCallback
import com.siliconlabs.bledemo.Bluetooth.Services.BluetoothService
import com.siliconlabs.bledemo.Bluetooth.Services.BluetoothService.GattConnectType
import com.siliconlabs.bledemo.ConnectedLighting.Activities.ConnectedLightingActivity
import com.siliconlabs.bledemo.HealthThermometer.Activities.HealthThermometerActivity
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.blinky.activities.BlinkyActivity
import com.siliconlabs.bledemo.blinky_thunderboard.activities.BlinkyThunderboardActivity
import com.siliconlabs.bledemo.environment.activities.EnvironmentActivity
import com.siliconlabs.bledemo.iop_test.activities.IOPTestActivity
import com.siliconlabs.bledemo.iop_test.models.IOPTest
import com.siliconlabs.bledemo.motion.activities.MotionActivity
import com.siliconlabs.bledemo.throughput.activities.ThroughputActivity
import com.siliconlabs.bledemo.throughput.utils.PeripheralManager
import com.siliconlabs.bledemo.thunderboard.utils.BleUtils
import com.siliconlabs.bledemo.wifi_commissioning.activities.WifiCommissioningActivity
import kotlinx.android.synthetic.main.dialog_select_device.*
import timber.log.Timber
import java.util.*
import kotlin.math.max

class SelectDeviceDialog : BaseDialogFragment(), BluetoothDiscoveryHost {
    private lateinit var adapter: ScannedDevicesAdapter
    private lateinit var itemDecoration: ItemDecoration
    private lateinit var layout: GridLayoutManager
    private lateinit var profilesInfo: ArrayList<Int>
    private lateinit var discovery: Discovery

    private var bluetoothService: BluetoothService? = null
    private var bluetoothBinding: BluetoothService.Binding? = null
    private var currentDeviceInfo: BluetoothDeviceInfo? = null
    private var connectType: GattConnectType? = null
    private var callback: Callback? = null

    private var retryAttempts = 0

    private var descriptionInfo = 0
    private var titleInfo = 0


    val timeoutGattCallback = object : TimeoutGattCallback() {

        override fun onTimeout() {
            (activity as BaseActivity).dismissModalDialog()
            Toast.makeText(activity, "Connection Timed Out", Toast.LENGTH_SHORT).show()
            bluetoothBinding?.unbind()
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    (activity as BaseActivity).dismissModalDialog()
                        when (connectType) {
                            GattConnectType.MOTION -> gatt.discoverServices()
                            GattConnectType.BLINKY -> {
                                if (gatt.device.name == "Blinky Example") {
                                    val intent = getIntent(connectType)
                                    if (intent != null) {
                                        activity?.startActivity(intent)
                                    }
                                } else {
                                    gatt.discoverServices()
                                }
                            }
                            else -> {
                                val intent = getIntent(connectType)
                                if (intent != null) {
                                    activity?.startActivity(intent)
                                }
                            }
                        }
                    retryAttempts = 0
                }
            } else if (status == 133) {
                if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    if (retryAttempts < RETRY_CONNECTION_COUNT) {
                        retryConnectionAttempt()
                    }
                }
            } else {
                (activity as BaseActivity).dismissModalDialog()
                bluetoothBinding?.unbind()
                activity?.runOnUiThread {
                    Toast.makeText(context, "Connection Failed", Toast.LENGTH_SHORT).show()
                }
                reDiscover(true)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            BleUtils.readCharacteristic(gatt, getModelNumberCharacteristic(gatt))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?,
                                          characteristic: BluetoothGattCharacteristic?,
                                          status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (characteristic?.uuid == GattCharacteristic.ModelNumberString.uuid) {
                when (connectType) {
                    GattConnectType.MOTION -> {
                        getIntent(connectType)?.let {
                            it.putExtra(MODEL_TYPE_EXTRA, characteristic.getStringValue(0))
                            startActivity(it)
                        }
                    }
                    GattConnectType.BLINKY -> {
                        when (characteristic.getStringValue(0)) {
                            "BRD4166A" -> BleUtils.readCharacteristic(gatt, getPowerSourceCharacteristic(gatt))
                            "BRD4184A",
                            "BRD4184B" -> {
                                getIntent(connectType)?.let {
                                    it.putExtra(MODEL_TYPE_EXTRA, characteristic.getStringValue(0))
                                    startActivity(it)
                                }
                            }
                            else -> { Timber.d("Unknown model")}
                        }
                    }
                    else -> { }
                }
            } else if (characteristic?.uuid == GattCharacteristic.PowerSource.uuid) {
                Intent(requireContext(), BlinkyThunderboardActivity::class.java).apply {
                    putExtra(POWER_SOURCE_EXTRA, characteristic.getIntValue(GattCharacteristic.PowerSource.format, 0))
                    startActivity(this)
                }
            }
        }
    }

    private fun getModelNumberCharacteristic(gatt: BluetoothGatt?): BluetoothGattCharacteristic? {
        val deviceInformationService = gatt?.getService(GattService.DeviceInformation.number)
        return deviceInformationService?.getCharacteristic(GattCharacteristic.ModelNumberString.uuid)
    }

    private fun getPowerSourceCharacteristic(gatt: BluetoothGatt?): BluetoothGattCharacteristic? {
        val service = gatt?.getService(GattService.PowerSource.number)
        return service?.getCharacteristic(GattCharacteristic.PowerSource.uuid)
    }

    private fun retryConnectionAttempt() {
        retryAttempts++
        activity?.runOnUiThread {
            Handler().postDelayed({
                bluetoothService?.connectGatt(currentDeviceInfo?.device!!, false, timeoutGattCallback)
            }, 1000)
        }
    }


    override fun onAttach(context: Context) {
        super.onAttach(context)

        adapter = ScannedDevicesAdapter(object : DeviceInfoViewHolder.Generator(R.layout.adapter_bluetooth_device) {
            override fun generate(itemView: View): DeviceInfoViewHolder {
                val holder = ViewHolder(itemView)
                holder.setOnClickListener(View.OnClickListener {
                    val adapterPos = holder.adapterPosition
                    if (adapterPos != RecyclerView.NO_POSITION) {
                        val devInfo = adapter.getDevicesInfo()[adapterPos]
                        currentDeviceInfo = devInfo

                        when (connectType) {
                            GattConnectType.RANGE_TEST -> {
                                dismiss()
                                callback?.getBluetoothDeviceInfo(currentDeviceInfo)
                            }
                            GattConnectType.IOP_TEST -> {
                                (activity as BaseActivity).dismissModalDialog()
                                IOPTest.createDataTest(devInfo.name ?: "IOP Test")
                                getIntent(connectType)?.let {
                                    activity?.startActivity(it)
                                }
                            }
                            else -> connect(devInfo)
                        }
                    }
                })
                return holder
            }
        }, context)

        discovery = Discovery(adapter, this)
        adapter.setThermometerMode()
        discovery.connect(context)
        layout = GridLayoutManager(context, context.resources.getInteger(R.integer.device_selection_columns), LinearLayoutManager.VERTICAL, false)

        itemDecoration = object : ItemDecoration() {
            val horizontalMargin = resources.getDimensionPixelSize(R.dimen.item_margin)
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val columns = layout.spanCount
                if (columns == 1) {
                    outRect[0, 0, 0] = 0
                } else {
                    val itemPos = parent.getChildAdapterPosition(view)
                    if (itemPos % columns == columns - 1) {
                        outRect[0, 0, 0] = 0
                    } else {
                        outRect[0, 0, horizontalMargin] = 0
                    }
                }
            }
        }
    }

    override fun onDetach() {
        discovery.disconnect()
        bluetoothBinding?.unbind()
        super.onDetach()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true

        arguments?.let { args ->
            titleInfo = args.getInt(TITLE_INFO)
            descriptionInfo = args.getInt(DESC_INFO)
            profilesInfo = args.getIntegerArrayList(PROFILES_INFO)!!
            connectType = GattConnectType.values()[args.getInt(CONN_TYPE_INFO, 0)]
        }

        adapter.setHasStableIds(true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        button_cancel.setOnClickListener {
            dialog?.dismiss()
            callback?.onCancel()
        }
        initializeRecyclerView()
    }

    override fun onCancel(dialog: DialogInterface) {
        callback?.onCancel()
        super.onCancel(dialog)
    }

    private fun initializeRecyclerView() {
        list.layoutManager = layout
        list.addItemDecoration(itemDecoration)
        list.setHasFixedSize(true)
        list.adapter = adapter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_select_device, container, false)
    }

    override fun onResume() {
        super.onResume()
        if (BluetoothAdapter.getDefaultAdapter() != null && BluetoothAdapter.getDefaultAdapter().isEnabled && dialog != null && dialog?.window != null) {
            val height = (resources.displayMetrics.heightPixels * 0.50).toInt()
            dialog?.window?.setLayout(LinearLayout.LayoutParams.WRAP_CONTENT, height)
            reDiscover(true)
        } else {
            dismiss()
        }
    }

    private fun reDiscover(clearCachedDiscoveries: Boolean) {
        if (BluetoothAdapter.getDefaultAdapter() != null && !BluetoothAdapter.getDefaultAdapter().isEnabled) {
            return
        }
        startDiscovery(discovery, clearCachedDiscoveries)
    }

    private fun startDiscovery(discovery: Discovery, clearCachedDiscoveries: Boolean) {
        discovery.clearFilters()

        when (connectType) {
            GattConnectType.THERMOMETER -> {
                discovery.addFilter(GattService.HealthThermometer)
            }
            GattConnectType.LIGHT -> {
                discovery.addFilter(GattService.ProprietaryLightService)
                discovery.addFilter(GattService.ZigbeeLightService)
                discovery.addFilter(GattService.ConnectLightService)
                discovery.addFilter(GattService.ThreadLightService)
            }
            GattConnectType.RANGE_TEST -> {
                discovery.addFilter(GattService.RangeTestService)
            }
            GattConnectType.BLINKY -> {
                discovery.addFilter("Blinky Example")
                discovery.addFilter(ManufacturerDataFilter(
                        id = 71,
                        data = byteArrayOf(2, 0)
                ))
            }
            GattConnectType.THROUGHPUT_TEST -> {
                discovery.addFilter("Throughput Test")
            }
            GattConnectType.WIFI_COMMISSIONING -> {
                discovery.addFilter("BLE_CONFIGURATOR")
            }
            GattConnectType.IOP_TEST -> {
                discovery.addFilter("IOP Test")
                discovery.addFilter("IOP_Test_1")
            }
            GattConnectType.MOTION -> {
                discovery.addFilter(ManufacturerDataFilter(
                        id = 71,
                        data = byteArrayOf(2, 0)
                ))
            }
            GattConnectType.ENVIRONMENT -> {
                discovery.addFilter(ManufacturerDataFilter(
                        id = 71,
                        data = byteArrayOf(2, 0)
                ))
            }
            else -> { }
        }

        discovery.startDiscovery(clearCachedDiscoveries)
    }

    override fun onPause() {
        super.onPause()
        discovery.stopDiscovery(true)
    }

    private fun connect(deviceInfo: BluetoothDeviceInfo) {
        currentDeviceInfo = deviceInfo
        retryAttempts = 0

        bluetoothBinding = object : BluetoothService.Binding(requireContext()) {
            override fun onBound(service: BluetoothService?) {
                if (connectType == GattConnectType.THROUGHPUT_TEST) {
                    PeripheralManager.advertiseThroughputServer(service)
                }

                (activity as BaseActivity).showModalDialog(BaseActivity.ConnectionStatus.CONNECTING, DialogInterface.OnCancelListener { service?.clearConnectedGatt() })
                service?.connectGatt(deviceInfo.device, false, timeoutGattCallback)
            }
        }
        bluetoothBinding?.bind()
    }

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    private fun getIntent(connectType: GattConnectType?): Intent? {
        return when (connectType) {
            GattConnectType.THERMOMETER -> {
                Intent(activity, HealthThermometerActivity::class.java)
            }
            GattConnectType.LIGHT -> {
                Intent(activity, ConnectedLightingActivity::class.java)
            }
            GattConnectType.BLINKY -> {
                Intent(activity, BlinkyActivity::class.java)
            }
            GattConnectType.THROUGHPUT_TEST -> {
                Intent(activity, ThroughputActivity::class.java)
            }
            GattConnectType.WIFI_COMMISSIONING -> {
                Intent(activity, WifiCommissioningActivity::class.java)
            }
            GattConnectType.IOP_TEST -> {
                Intent(activity, IOPTestActivity::class.java)
            }
            GattConnectType.MOTION -> {
                Intent(activity, MotionActivity::class.java)
            }
            GattConnectType.ENVIRONMENT -> {
                Intent(activity, EnvironmentActivity::class.java)
            }
            else -> null
        }
    }

    override fun isReady(): Boolean {
        return isResumed
    }

    override fun reDiscover() {
        reDiscover(false)
    }

    override fun onAdapterDisabled() {}
    override fun onAdapterEnabled() {}

    class ViewHolder(itemView: View) : DeviceInfoViewHolder(itemView) {
        private var btInfo: BluetoothDeviceInfo? = null
        var protocolIcon = itemView.findViewById(android.R.id.icon2) as ImageView
        var icon = itemView.findViewById(android.R.id.icon) as ImageView
        var title = itemView.findViewById(android.R.id.title) as TextView

        override fun setData(info: BluetoothDeviceInfo, position: Int, size: Int) {
            this.btInfo = info
            val scanInfo = info.scanInfo
            val displayName = scanInfo?.getDisplayName()
            title.text = displayName
            val rssi = max(0, scanInfo?.rssi!! + 80)
            icon.setImageLevel(rssi)

            if (scanInfo.scanRecord?.serviceUuids != null) {
                when {
                    scanInfo.scanRecord?.serviceUuids?.contains(ParcelUuid(GattService.ZigbeeLightService.number))!! -> {
                        protocolIcon.setImageResource(R.drawable.icon_zigbee)
                        protocolIcon.visibility = View.VISIBLE
                    }
                    scanInfo.scanRecord?.serviceUuids?.contains(ParcelUuid(GattService.ProprietaryLightService.number))!! -> {
                        protocolIcon.setImageResource(R.drawable.icon_proprietary)
                        protocolIcon.visibility = View.VISIBLE
                    }
                    scanInfo.scanRecord?.serviceUuids?.contains(ParcelUuid(GattService.ConnectLightService.number))!! -> {
                        protocolIcon.setImageResource(R.drawable.icon_connect)
                        protocolIcon.visibility = View.VISIBLE
                    }
                    scanInfo.scanRecord?.serviceUuids?.contains(ParcelUuid(GattService.ThreadLightService.number))!! -> {
                        protocolIcon.setImageResource(R.drawable.icon_thread)
                        protocolIcon.visibility = View.VISIBLE
                    }
                    else -> {
                        protocolIcon.visibility = View.GONE
                    }
                }
            }

            itemView.setOnClickListener(this)
        }
    }

    interface Callback {
        fun onCancel()
        fun getBluetoothDeviceInfo(info: BluetoothDeviceInfo?)
    }

    companion object {
        private const val TITLE_INFO = "_title_info_"
        private const val DESC_INFO = "_desc_info_"
        private const val PROFILES_INFO = "_profiles_info_"
        private const val CONN_TYPE_INFO = "_conn_type_info_"

        private const val RETRY_CONNECTION_COUNT = 2

        const val MODEL_TYPE_EXTRA = "model type"
        const val POWER_SOURCE_EXTRA = "power source"

        fun newDialog(titleInfo: Int, descriptionInfo: Int, profilesInfo: List<Pair<Int, Int>>?, connectType: GattConnectType?): SelectDeviceDialog {
            val dialog = SelectDeviceDialog()
            val args = Bundle()
            args.putInt(TITLE_INFO, titleInfo)
            args.putInt(DESC_INFO, descriptionInfo)
            val profilesInfoList = ArrayList<Int>()
            if (profilesInfo != null) {
                for (profileInfo in profilesInfo) {
                    profilesInfoList.add(profileInfo.first)
                    profilesInfoList.add(profileInfo.second)
                }
            }
            args.putIntegerArrayList(PROFILES_INFO, profilesInfoList)
            if (connectType != null) {
                args.putInt(CONN_TYPE_INFO, connectType.ordinal)
            }
            dialog.arguments = args
            return dialog
        }
    }
}