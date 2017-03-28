package it.zenitlab.cordova.plugins.zbtprinter;

import java.io.IOException;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;

import org.json.JSONArray;
import org.json.JSONException;

import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;

import com.zebra.sdk.device.ZebraIllegalArgumentException;
import com.zebra.sdk.printer.PrinterStatus;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.ZebraPrinterLinkOs;

import com.zebra.sdk.graphics.internal.ZebraImageAndroid;
import com.zebra.sdk.printer.discovery.BluetoothDiscoverer;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;

import android.os.Looper;
import android.util.Log;
import android.util.Base64;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import android.bluetooth.BluetoothAdapter;

public class ZebraBluetoothPrinter extends CordovaPlugin implements DiscoveryHandler {

    private static final String LOG_TAG = "ZebraBluetoothPrinter";
    private CallbackContext callbackContext;
    private boolean printerFound;
    private int SEARCH_NEW_PRINTERS = -1;

    public ZebraBluetoothPrinter() {

    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        if (action.equals("printImage")) {
            try {
                JSONArray base64Array = args.getJSONArray(0);
                String MACAddress = args.getString(1);
                sendImage(base64Array, MACAddress);
            } catch (IOException e) {
                Log.e(LOG_TAG, e.getMessage());
                e.printStackTrace();
            }
            return true;
        } else if (action.equals("discoverPrinters")) {
            discoverPrinters();
            return true;
        }

        return false;
    }

    private void sendImage(final JSONArray base64Array, final String MACAddress) throws IOException {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    if (bluetoothAdapter.isEnabled()) {

                        Connection thePrinterConn = new BluetoothConnection(MACAddress);

                        if (isPrinterReady(thePrinterConn)) {

                            thePrinterConn.open();

                            ZebraPrinter printer = ZebraPrinterFactory.getInstance(thePrinterConn);

                            for (int i = base64Array.length() - 1; i >= 0; i--) {
                                byte[] decodedString = Base64.decode(base64Array.get(i).toString(), Base64.DEFAULT);
                                Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                                ZebraImageAndroid zebraimage = new ZebraImageAndroid(decodedByte);

                                //Lengte van het label eerst instellen om te kleine of te grote afdruk te voorkomen
                                if (i == base64Array.length() - 1) {
                                    setLabelLength(printer, zebraimage);
                                }

                                printImage(zebraimage, printer, thePrinterConn);
                            }

                            //Zie dat de data zeker tot de printer geraakt voordat de connectie sluit
                            Thread.sleep(500);

                            thePrinterConn.close();

                            callbackContext.success();
                        } else {
                            callbackContext.error("Printer is nog niet klaar.");
                        }

                    } else {
                        callbackContext.error("Bluetooth staat niet aan.");
                    }
                    //Indien socket gesloten is, -1 meegeven zodat er gezocht wordt naar nieuwe printers.
                } catch (ConnectionException e) {
                    if (e.getMessage().toLowerCase().contains("socket might closed")) {
                        callbackContext.error(SEARCH_NEW_PRINTERS);
                    } else {
                        callbackContext.error(e.getMessage());
                    }

                } catch (Exception e) {
                    callbackContext.error(e.getMessage());
                }
            }
        }).start();
    }

    private void printImage(ZebraImageAndroid zebraimage, ZebraPrinter printer, Connection thePrinterConn) throws ConnectionException, ZebraIllegalArgumentException {
        ZebraPrinterLinkOs zebraPrinterLinkOs = ZebraPrinterFactory.createLinkOsPrinter(printer);

        //Gebruik de Zebra Android sdk om te printen indien de printer LINK-OS ondersteunt
        if (zebraPrinterLinkOs != null) {
            printer.printImage(zebraimage, 150, 0, zebraimage.getWidth(), zebraimage.getHeight(), false);

            //Gebruik de oude manier om de data te printen indien LINK-OS niet ondersteund wordt
        } else {
            printer.storeImage("wgkimage.pcx", zebraimage, -1, -1);

            String cpcl = "! 0 200 200 ";
            cpcl += zebraimage.getHeight();
            cpcl += " 1\r\n";
            cpcl += "PW 750\r\nTONE 0\r\nSPEED 6\r\nSETFF 203 5\r\nON - FEED FEED\r\nNO - PACE\r\nJOURNAL\r\n";
            cpcl += "PCX 150 0 !<wgkimage.pcx\r\n";
            cpcl += "FORM\r\n";
            cpcl += "PRINT\r\n";
            thePrinterConn.write(cpcl.getBytes());
        }

    }

    /**
     * Gebruik de Zebra Android SDK om de lengte te bepalen indien de printer LINK-OS ondersteunt
     * @param printer
     * @param zebraimage
     * @throws Exception
     */
    private void setLabelLength(ZebraPrinter printer, ZebraImageAndroid zebraimage) throws Exception {
        ZebraPrinterLinkOs zebraPrinterLinkOs = ZebraPrinterFactory.createLinkOsPrinter(printer);

        if (zebraPrinterLinkOs != null) {
            String currentLabelLength = zebraPrinterLinkOs.getSettingValue("zpl.label_length");
            if (!currentLabelLength.equals(String.valueOf(zebraimage.getHeight()))) {
                zebraPrinterLinkOs.setSetting("zpl.label_length", zebraimage.getHeight() + "");
            }
        }
    }

    private Boolean isPrinterReady(Connection connection) throws Exception {
        Boolean isOK = false;
        connection.open();
        // Creates a ZebraPrinter object to use Zebra specific functionality like getCurrentStatus()
        ZebraPrinter printer = ZebraPrinterFactory.getInstance(connection);
        PrinterStatus printerStatus = printer.getCurrentStatus();
        if (printerStatus.isReadyToPrint) {
            isOK = true;
        } else if (printerStatus.isPaused) {
            throw new Exception("Printer is gepauzeerd. Gelieve deze eerst te activeren.");
        } else if (printerStatus.isHeadOpen) {
            throw new Exception("Printer staat open. Gelieve deze eerst te sluiten.");
        } else if (printerStatus.isPaperOut) {
            throw new Exception("Gelieve eerst de etiketten aan te vullen.");
        } else {
            throw new Exception("Onbekende printerfout opgetreden.");
        }
        return isOK;
    }

    private void discoverPrinters() {
        printerFound = false;
        new Thread(new Runnable() {
            public void run() {
                Looper.prepare();
                try {

                    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    if (bluetoothAdapter.isEnabled()) {
                        BluetoothDiscoverer.findPrinters(cordova.getActivity().getApplicationContext(), ZebraBluetoothPrinter.this);
                    } else {
                        callbackContext.error("Bluetooth staat niet aan.");
                    }

                } catch (ConnectionException e) {
                    callbackContext.error(e.getMessage());
                } finally {
                    Looper.myLooper().quit();
                }
            }
        }).start();
    }

    @Override
    public void foundPrinter(DiscoveredPrinter discoveredPrinter) {
        if (!printerFound) {
            printerFound = true;
            callbackContext.success(discoveredPrinter.address);
        }
    }

    @Override
    public void discoveryFinished() {
        if (!printerFound) {
            callbackContext.error("Geen printer gevonden.");
        }
    }

    @Override
    public void discoveryError(String s) {
        callbackContext.error(s);
    }
}
