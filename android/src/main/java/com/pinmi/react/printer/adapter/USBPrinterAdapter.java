package com.pinmi.react.printer.adapter;

import static com.pinmi.react.printer.adapter.UtilsImage.getPixelsSlow;
import static com.pinmi.react.printer.adapter.UtilsImage.recollectSlice;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;


import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by xiesubin on 2017/9/20.
 */

public class USBPrinterAdapter implements PrinterAdapter {
    @SuppressLint("StaticFieldLeak")
    private static USBPrinterAdapter mInstance;


    private final String LOG_TAG = "RNUSBPrinter";
    private Context mContext;
    private UsbManager mUSBManager;
    private PendingIntent mPermissionIndent;
    private UsbDevice mUsbDevice;
    private UsbDeviceConnection mUsbDeviceConnection;
    private UsbInterface mUsbInterface;
    private UsbEndpoint mEndPoint;
    private static final String ACTION_USB_PERMISSION = "com.pinmi.react.USBPrinter.USB_PERMISSION";
    private static final String EVENT_USB_DEVICE_ATTACHED = "usbAttached";
    private static final String EVENT_USB_DEVICE_DETACHED = "usbDetached";

    private Callback mPendingPermissionSuccessCallback;
    private Callback mPendingPermissionErrorCallback;
    private UsbDevice mPendingPermissionDevice;

    private final static char ESC_CHAR = 0x1B;
    private static final byte[] SELECT_BIT_IMAGE_MODE = {0x1B, 0x2A, 33};
    private final static byte[] SET_LINE_SPACE_24 = new byte[]{ESC_CHAR, 0x33, 24};
    private final static byte[] SET_LINE_SPACE_32 = new byte[]{ESC_CHAR, 0x33, 32};
    private final static byte[] LINE_FEED = new byte[]{0x0A};
    private static final byte[] CENTER_ALIGN = {0x1B, 0X61, 0X31};

    private USBPrinterAdapter() {
    }

    public static USBPrinterAdapter getInstance() {
        if (mInstance == null) {
            mInstance = new USBPrinterAdapter();
        }
        return mInstance;
    }

    private final BroadcastReceiver mUsbDeviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        assert usbDevice != null;
                        Log.i(LOG_TAG, "success to grant permission for device " + usbDevice.getDeviceId() + ", vendor_id: " + usbDevice.getVendorId() + " product_id: " + usbDevice.getProductId());
                        mUsbDevice = usbDevice;
                        if (mPendingPermissionSuccessCallback != null && isSameDevice(usbDevice, mPendingPermissionDevice)) {
                            mPendingPermissionSuccessCallback.invoke(new USBPrinterDevice(usbDevice).toRNWritableMap());
                        }
                    } else {
                        assert usbDevice != null;
                        Toast.makeText(context, "User refuses to obtain USB device permissions" + usbDevice.getDeviceName(), Toast.LENGTH_LONG).show();
                        if (mPendingPermissionErrorCallback != null && isSameDevice(usbDevice, mPendingPermissionDevice)) {
                            mPendingPermissionErrorCallback.invoke("User refuses to obtain USB device permissions");
                        }
                    }
                    clearPendingPermissionCallbacks();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                synchronized (this) {
                    UsbDevice detachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean isCurrentDeviceDetached =
                            mUsbDevice != null && (detachedDevice == null || isSameDevice(mUsbDevice, detachedDevice));
                    if (isCurrentDeviceDetached) {
                        Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show();
                        closeConnectionIfExists();
                        mUsbDevice = null;
                    }
                    emitUsbEvent(EVENT_USB_DEVICE_DETACHED, detachedDevice);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                synchronized (this) {
                    UsbDevice attachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (attachedDevice != null && isUsbPrinter(attachedDevice)) {
                        emitUsbEvent(EVENT_USB_DEVICE_ATTACHED, attachedDevice);
                    }
                }
            }
        }
    };

    @SuppressLint("UnspecifiedImmutableFlag")
    public void init(ReactApplicationContext reactContext, Callback successCallback, Callback errorCallback) {
        this.mContext = reactContext;
        this.mUSBManager = (UsbManager) this.mContext.getSystemService(Context.USB_SERVICE);
        this.mPermissionIndent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        mContext.registerReceiver(mUsbDeviceReceiver, filter);
        Log.v(LOG_TAG, "RNUSBPrinter initialized");
        successCallback.invoke();
    }


    public void closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            if (mUsbInterface != null) {
                mUsbDeviceConnection.releaseInterface(mUsbInterface);
            }
            mUsbDeviceConnection.close();
            mUsbInterface = null;
            mEndPoint = null;
            mUsbDeviceConnection = null;
        }
    }

    public List<PrinterDevice> getDeviceList(Callback errorCallback) {
        List<PrinterDevice> lists = new ArrayList<>();
        if (mUSBManager == null) {
            errorCallback.invoke("USBManager is not initialized while get device list");
            return lists;
        }

        for (UsbDevice usbDevice : mUSBManager.getDeviceList().values()) {
            if (isUsbPrinter(usbDevice)) {
                lists.add(new USBPrinterDevice(usbDevice));
            }
        }
        return lists;
    }

    private boolean isUsbPrinter(UsbDevice usbDevice) {
        for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            if (usbDevice.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                return true;
            }
        }
        return false;
    }


    @Override
    public void selectDevice(PrinterDeviceId printerDeviceId, Callback successCallback, Callback errorCallback) {
        if (mUSBManager == null) {
            errorCallback.invoke("USBManager is not initialized before select device");
            return;
        }

        USBPrinterDeviceId usbPrinterDeviceId = (USBPrinterDeviceId) printerDeviceId;
        clearPendingPermissionCallbacks();
        List<UsbDevice> usbDevices = new ArrayList<>(mUSBManager.getDeviceList().values());
        if (usbDevices.size() == 0) {
            errorCallback.invoke("Device list is empty, can not choose device");
            return;
        }
        for (UsbDevice usbDevice : usbDevices) {
            if (usbDevice.getVendorId() == usbPrinterDeviceId.getVendorId() && usbDevice.getProductId() == usbPrinterDeviceId.getProductId()) {
                Log.v(LOG_TAG, "request for device: vendor_id: " + usbPrinterDeviceId.getVendorId() + ", product_id: " + usbPrinterDeviceId.getProductId());
                closeConnectionIfExists();
                mUsbDevice = usbDevice;
                if (mUSBManager.hasPermission(usbDevice)) {
                    successCallback.invoke(new USBPrinterDevice(usbDevice).toRNWritableMap());
                    return;
                }
                mPendingPermissionSuccessCallback = successCallback;
                mPendingPermissionErrorCallback = errorCallback;
                mPendingPermissionDevice = usbDevice;
                mUSBManager.requestPermission(usbDevice, mPermissionIndent);
                return;
            }
        }

        errorCallback.invoke("can not find specified device");
        return;
    }

    private boolean openConnection(Callback errorCallback) {
        if (mUsbDevice == null) {
            Log.e(LOG_TAG, "USB Deivce is not initialized");
            errorCallback.invoke("USB device is not initialized, reconnect the printer first");
            return false;
        }
        if (mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized");
            errorCallback.invoke("USB manager is not initialized");
            return false;
        }
        if (!mUSBManager.hasPermission(mUsbDevice)) {
            closeConnectionIfExists();
            clearPendingPermissionCallbacks();
            mPendingPermissionDevice = mUsbDevice;
            mUSBManager.requestPermission(mUsbDevice, mPermissionIndent);
            errorCallback.invoke("USB permission is missing, permission requested. Please retry printing.");
            return false;
        }

        if (mUsbDeviceConnection != null) {
            Log.i(LOG_TAG, "USB Connection already connected");
            return true;
        }

        UsbInterface usbInterface = mUsbDevice.getInterface(0);
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            final UsbEndpoint ep = usbInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    UsbDeviceConnection usbDeviceConnection = mUSBManager.openDevice(mUsbDevice);
                    if (usbDeviceConnection == null) {
                        Log.e(LOG_TAG, "failed to open USB Connection");
                        return false;
                    }
                    if (usbDeviceConnection.claimInterface(usbInterface, true)) {

                        mEndPoint = ep;
                        mUsbInterface = usbInterface;
                        mUsbDeviceConnection = usbDeviceConnection;
                        Log.i(LOG_TAG, "Device connected");
                        return true;
                    } else {
                        usbDeviceConnection.close();
                        Log.e(LOG_TAG, "failed to claim usb connection");
                        return false;
                    }
                }
            }
        }
        return true;
    }


    public void printRawData(String data, Callback errorCallback) {
        final String rawData = data;
        Log.v(LOG_TAG, "start to print raw data " + data);
        boolean isConnected = openConnection(errorCallback);
        if (isConnected) {
            Log.v(LOG_TAG, "Connected to device");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] bytes = Base64.decode(rawData, Base64.DEFAULT);
                    int b = mUsbDeviceConnection.bulkTransfer(mEndPoint, bytes, bytes.length, 100000);
                    Log.i(LOG_TAG, "Return Status: b-->" + b);
                    if (b < 0) {
                        closeConnectionIfExists();
                        errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                    }
                }
            }).start();
        } else {
            String msg = "failed to connected to device";
            Log.v(LOG_TAG, msg);
            errorCallback.invoke(msg);
        }
    }

    public static Bitmap getBitmapFromURL(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap myBitmap = BitmapFactory.decodeStream(input);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            myBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);

            return myBitmap;
        } catch (IOException e) {
            // Log exception
            return null;
        }
    }


    @Override
    public void printImageData(final String imageUrl, int imageWidth, int imageHeight, Callback errorCallback) {
        final Bitmap bitmapImage = getBitmapFromURL(imageUrl);

        if (bitmapImage == null) {
            errorCallback.invoke("image not found");
            return;
        }

        Log.v(LOG_TAG, "start to print image data " + bitmapImage);
        boolean isConnected = openConnection(errorCallback);
        if (isConnected) {
            Log.v(LOG_TAG, "Connected to device");
            int[][] pixels = getPixelsSlow(bitmapImage, imageWidth, imageHeight);

            if (!writeToUsb(SET_LINE_SPACE_24)) {
                closeConnectionIfExists();
                errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                return;
            }

            if (!writeToUsb(CENTER_ALIGN)) {
                closeConnectionIfExists();
                errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                return;
            }

            for (int y = 0; y < pixels.length; y += 24) {
                // Like I said before, when done sending data,
                // the printer will resume to normal text printing
                if (!writeToUsb(SELECT_BIT_IMAGE_MODE)) {
                    closeConnectionIfExists();
                    errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                    return;
                }

                // Set nL and nH based on the width of the image
                byte[] row = new byte[]{(byte) (0x00ff & pixels[y].length)
                        , (byte) ((0xff00 & pixels[y].length) >> 8)};

                if (!writeToUsb(row)) {
                    closeConnectionIfExists();
                    errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                    return;
                }

                for (int x = 0; x < pixels[y].length; x++) {
                    // for each stripe, recollect 3 bytes (3 bytes = 24 bits)
                    byte[] slice = recollectSlice(y, x, pixels);
                    if (!writeToUsb(slice)) {
                        closeConnectionIfExists();
                        errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                        return;
                    }
                }

                // Do a line feed, if not the printing will resume on the same line
                if (!writeToUsb(LINE_FEED)) {
                    closeConnectionIfExists();
                    errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                    return;
                }
            }

            if (!writeToUsb(SET_LINE_SPACE_32) || !writeToUsb(LINE_FEED)) {
                closeConnectionIfExists();
                errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
            }
        } else {
            String msg = "failed to connected to device";
            Log.v(LOG_TAG, msg);
            errorCallback.invoke(msg);
        }

    }

    @Override
    public void printImageBase64(final Bitmap bitmapImage, int imageWidth, int imageHeight, Callback errorCallback) {
        if (bitmapImage == null) {
            errorCallback.invoke("image not found");
            return;
        }

        Log.v(LOG_TAG, "start to print image data " + bitmapImage);
        boolean isConnected = openConnection(errorCallback);
        if (isConnected) {
            Log.v(LOG_TAG, "Connected to device");
            int[][] pixels = getPixelsSlow(bitmapImage, imageWidth, imageHeight);

            if (!writeToUsb(SET_LINE_SPACE_24)) {
                closeConnectionIfExists();
                errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                return;
            }

            if (!writeToUsb(CENTER_ALIGN)) {
                closeConnectionIfExists();
                errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                return;
            }

            for (int y = 0; y < pixels.length; y += 24) {
                // Like I said before, when done sending data,
                // the printer will resume to normal text printing
                if (!writeToUsb(SELECT_BIT_IMAGE_MODE)) {
                    closeConnectionIfExists();
                    errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                    return;
                }

                // Set nL and nH based on the width of the image
                byte[] row = new byte[]{(byte) (0x00ff & pixels[y].length)
                        , (byte) ((0xff00 & pixels[y].length) >> 8)};

                if (!writeToUsb(row)) {
                    closeConnectionIfExists();
                    errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                    return;
                }

                for (int x = 0; x < pixels[y].length; x++) {
                    // for each stripe, recollect 3 bytes (3 bytes = 24 bits)
                    byte[] slice = recollectSlice(y, x, pixels);
                    if (!writeToUsb(slice)) {
                        closeConnectionIfExists();
                        errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                        return;
                    }
                }

                // Do a line feed, if not the printing will resume on the same line
                if (!writeToUsb(LINE_FEED)) {
                    closeConnectionIfExists();
                    errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
                    return;
                }
            }

            if (!writeToUsb(SET_LINE_SPACE_32) || !writeToUsb(LINE_FEED)) {
                closeConnectionIfExists();
                errorCallback.invoke("USB write failed, connection may be lost. Reconnect and retry.");
            }
        } else {
            String msg = "failed to connected to device";
            Log.v(LOG_TAG, msg);
            errorCallback.invoke(msg);
        }

    }

    private void emitUsbEvent(String eventName, UsbDevice usbDevice) {
        if (mContext != null) {
            ((ReactApplicationContext) mContext).getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, usbDevice != null ? new USBPrinterDevice(usbDevice).toRNWritableMap() : null);
        }
    }

    private void clearPendingPermissionCallbacks() {
        mPendingPermissionSuccessCallback = null;
        mPendingPermissionErrorCallback = null;
        mPendingPermissionDevice = null;
    }

    private boolean isSameDevice(UsbDevice left, UsbDevice right) {
        return left != null
                && right != null
                && left.getDeviceId() == right.getDeviceId()
                && left.getVendorId() == right.getVendorId()
                && left.getProductId() == right.getProductId();
    }

    private boolean writeToUsb(byte[] bytes) {
        if (mUsbDeviceConnection == null || mEndPoint == null) {
            return false;
        }
        int transferred = mUsbDeviceConnection.bulkTransfer(mEndPoint, bytes, bytes.length, 100000);
        return transferred >= 0;
    }
}
