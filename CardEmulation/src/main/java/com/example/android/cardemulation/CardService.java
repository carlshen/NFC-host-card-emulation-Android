/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.cardemulation;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import com.jeremyliao.liveeventbus.LiveEventBus;

import org.simalliance.openmobileapi.Channel;
import org.simalliance.openmobileapi.Reader;
import org.simalliance.openmobileapi.SEService;
import org.simalliance.openmobileapi.Session;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/**
 * This is a sample APDU Service which demonstrates how to interface with the card emulation support
 * added in Android 4.4, KitKat.
 *
 * <p>This sample replies to any requests sent with the string "Hello World". In real-world
 * situations, you would need to modify this code to implement your desired communication
 * protocol.
 *
 * <p>This sample will be invoked for any terminals selecting AIDs of 0xF11111111, 0xF22222222, or
 * 0xF33333333. See src/main/res/xml/aid_list.xml for more details.
 *
 * <p class="note">Note: This is a low-level interface. Unlike the NdefMessage many developers
 * are familiar with for implementing Android Beam in apps, card emulation only provides a
 * byte-array based communication channel. It is left to developers to implement higher level
 * protocol support as needed.
 */
public class CardService extends HostApduService {
    private static final String TAG = "CardService";
    public static final String KEY_TEST_OBSERVE = "key_test_observe";
    // next is test aid
    private static final byte[] ISD_AID = new byte[] { (byte) 0xA0, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00 };
    // AID for our loyalty card service.
//    private static final String SAMPLE_LOYALTY_CARD_AID = "A00000015141434C00";
    private static final String SAMPLE_LOYALTY_CARD_AID = "A0000000031010";
    // AID for our select card service.
    private static String SELECT_CARD_AID = SAMPLE_LOYALTY_CARD_AID;
    // ISO-DEP command HEADER for selecting an AID.
    // Format: [Class | Instruction | Parameter 1 | Parameter 2]
    private static final String SELECT_APDU_HEADER = "00A40400";
    // Format: [Class | Instruction | Parameter 1 | Parameter 2]
    private static final String GET_DATA_APDU_HEADER = "00CA0000";
    // "OK" status word sent in response to SELECT AID command (0x9000)
    private static final byte[] SELECT_OK_SW = HexStringToByteArray("9000");
    // "UNKNOWN" status word sent in response to invalid APDU command (0x0000)
    private static final byte[] UNKNOWN_CMD_SW = HexStringToByteArray("0000");
    private static final byte[] SELECT_APDU = BuildSelectApdu(SAMPLE_LOYALTY_CARD_AID);
    private static final byte[] GET_DATA_APDU = BuildGetDataApdu();

    private HandlerThread apduHandlerThread = null;
    private ApduHandler apduHandler = null;
    private static final int SEND_DATA_APDU = 1;
    public static final int REPLY_DATA_APDU = 2;
    SEService _service = null;
    Session _session = null;
    Channel _channel = null;
    SEServiceCallback callback = null;
    Handler handler = new Handler();
    protected volatile static boolean ese_service = false;
    ConcurrentLinkedQueue<String> conLinkedQueue = new ConcurrentLinkedQueue<>();
    CountDownLatch latch = new CountDownLatch(1);

    class  ApduHandler extends Handler {
        ApduHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg == null) {
                return;
            }
            switch (msg.what) {
                case SEND_DATA_APDU:
                    sendApduData();
                    break;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (apduHandler != null) {
            apduHandler.removeCallbacksAndMessages(null);
            apduHandler = null;
        }
        if (apduHandlerThread != null) {
            apduHandlerThread.quitSafely();
            apduHandlerThread = null;
        }
    }

    /**
     * Called if the connection to the NFC card is lost, in order to let the application know the
     * cause for the disconnection (either a lost link, or another AID being selected by the
     * reader).
     *
     * @param reason Either DEACTIVATION_LINK_LOSS or DEACTIVATION_DESELECTED
     */
    @Override
    public void onDeactivated(int reason) {
        Log.i(TAG, "onDeactivated reason: " + reason);
        if (_channel != null) {
            _channel.close();
            _channel = null;
        }
        if (_session != null) {
            _session.close();
            _session = null;
        }
        if (_service != null) {
            _service.shutdown();
            _service = null;
        }
        ese_service = false;
    }

    /**
     * This method will be called when a command APDU has been received from a remote device. A
     * response APDU can be provided directly by returning a byte-array in this method. In general
     * response APDUs must be sent as quickly as possible, given the fact that the user is likely
     * holding his device over an NFC reader when this method is called.
     *
     * <p class="note">If there are multiple services that have registered for the same AIDs in
     * their meta-data entry, you will only get called if the user has explicitly selected your
     * service, either as a default or just for the next tap.
     *
     * <p class="note">This method is running on the main thread of your application. If you
     * cannot return a response APDU immediately, return null and use the {@link
     * #sendResponseApdu(byte[])} method later.
     *
     * @param commandApdu The APDU that received from the remote device
     * @param extras A bundle containing extra data. May be null.
     * @return a byte-array containing the response APDU, or null if no response APDU can be sent
     * at this point.
     */

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        Log.i(TAG, "Received APDU: " + ByteArrayToHexString(commandApdu));
        if (apduHandlerThread == null) {
            apduHandlerThread = new HandlerThread("CardService");
            apduHandlerThread.start();
            apduHandler = new ApduHandler(apduHandlerThread.getLooper());
        }
        if (commandApdu == null || commandApdu.length < 2) {
            return null;
        }
        // queue the apdu, send message to apdu thread
        conLinkedQueue.offer(ByteArrayToHexString(commandApdu));
        if (ese_service) {
            apduHandler.sendEmptyMessage(SEND_DATA_APDU);
        } else {
            // firstly need connect se service
            ConnectSeService();
            apduHandler.sendEmptyMessageDelayed(SEND_DATA_APDU, 100);
        }
        // just respond null or select id, then respond result after process
        if ((commandApdu[1] == (byte)0xa4)/* && (commandApdu[4] == (byte)0x06)*/) {
            LiveEventBus.get()
                    .with(KEY_TEST_OBSERVE)
                    .post(ByteArrayToHexString(commandApdu));
//            LiveEventBus.get()
//                    .with(CardLogFragment.KEY_TEST_OBSERVE)
//                    .post("363232323232323230303030303030319000");
            return HexStringToByteArray("363232323232323230303030303030319000");
        }
        return null;
    }

    /**
     * Callback interface if informs that this SEService is connected to the SmartCardService
     */
    public class SEServiceCallback implements SEService.CallBack {
        public void serviceConnected(SEService service) {
            Log.i(TAG, "serviceConnected: ");
            _service = service;
            ese_service = true;
            latch.countDown();
        }
    }

    private void ConnectSeService() {
        Log.i(TAG, "ConnectSeService: ");
        if (callback == null) {
            callback = new SEServiceCallback();
        }
        if (!ese_service) {
            Log.i(TAG, "ConnectSeService firstly need connect with SeService.");
            new SEService(this, callback);
        }
    }

    private void sendApduData() {
        Log.i(TAG, "sendApduData: ");
        try {
            latch.await();
        } catch (InterruptedException arg2) {
            arg2.printStackTrace();
        }
        if (conLinkedQueue.isEmpty()) {
            Log.e(TAG, "Why queue is empty? Maybe just end. ");
            return;
        }
        byte[] commandApdu = HexStringToByteArray(conLinkedQueue.poll());
        if (commandApdu == null) {
            Log.e(TAG, "Why command Apdu is empty?");
            return;
        }
        if (_service == null || !_service.isConnected()) {
            Log.e(TAG, "Why _service is not connected?");
            LiveEventBus.get()
                    .with(KEY_TEST_OBSERVE)
                    .post("Why _service is not connected?");
            return;
        }
        if (_channel != null && !_channel.isClosed()) {
            if ((commandApdu[1] == (byte)0xa4)/* && (commandApdu[4] == (byte)0x06)*/) {
                Log.i(TAG, "Selection already processed on above step.");
                LiveEventBus.get()
                        .with(KEY_TEST_OBSERVE)
                        .post("Selection already processed on above step.");
            } else {
                try {
                    Log.i(TAG, "process command = " + ByteArrayToHexString(commandApdu));
                    LiveEventBus.get()
                            .with(KEY_TEST_OBSERVE)
                            .post(ByteArrayToHexString(commandApdu));
                    final byte[] rsp = _channel.transmit(commandApdu);
                    Log.i(TAG, "process response = " + ByteArrayToHexString(rsp));
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            sendResponseApdu(rsp);
                        }
                    });
                    LiveEventBus.get()
                            .with(KEY_TEST_OBSERVE)
                            .post(ByteArrayToHexString(rsp));
                } catch (Exception arg6) {
                    arg6.printStackTrace();
                }
            }
            return;
        }
        Reader[] readers = _service.getReaders();
        if (readers == null || readers.length < 1) {
            Log.e(TAG, "Why readers is empty?");
            LiveEventBus.get()
                    .with(KEY_TEST_OBSERVE)
                    .post("Why readers is not connected?");
            return;
        }
        Log.i(TAG, "process readers.length = " + readers.length);
        for (Reader reader : readers) {
            if (reader == null || reader.getName() == null) {
                Log.e(TAG, "Why reader is empty?");
                continue;
            }
//            Log.i(TAG, "process reader.getName() = " + reader.getName());
            if (reader.getName().startsWith("SIM")) {
                Log.i(TAG, "process reader name = " + reader.getName());
                if (_session == null || _session.isClosed()) {
                    try {
                        _session = reader.openSession();
                    } catch (Exception arg3) {
                        arg3.printStackTrace();
                        LiveEventBus.get()
                                .with(KEY_TEST_OBSERVE)
                                .post(arg3.getMessage());
                    }
                }
                if (_session == null || _session.isClosed()) {
                    Log.e(TAG, "Why _session is empty?");
                    continue;
                }
//                try {
//                    byte[] atr = _session.getATR();
//                    Log.i(TAG, "process getATR = " + ByteArrayToHexString(atr));
//                } catch (Exception arg2) {
//                    arg2.printStackTrace();
//                }
                if (_channel == null || _channel.isClosed()) {
                    if ((commandApdu[1] == (byte)0xa4)/* && (commandApdu[4] == (byte)0x06)*/) {
                        SELECT_CARD_AID = ByteArrayToHexString(commandApdu).substring(10);
                        if (TextUtils.isEmpty(SELECT_CARD_AID)) {
                            Log.e(TAG, "Why SELECT_CARD_AID is empty?");
                            SELECT_CARD_AID = SAMPLE_LOYALTY_CARD_AID;
                        } else {
                            Log.i(TAG, "process SELECT_CARD_AID = " + SELECT_CARD_AID);
                        }
                    }
                    try {
//                        _channel = _session.openBasicChannel(HexStringToByteArray(SELECT_CARD_AID));
                        _channel = _session.openLogicalChannel(HexStringToByteArray(SELECT_CARD_AID));
                    } catch (Exception arg5) {
                        arg5.printStackTrace();
                        LiveEventBus.get()
                                .with(KEY_TEST_OBSERVE)
                                .post(arg5.getMessage());
                    }
                    if (_channel == null || _channel.isClosed()) {
                        Log.e(TAG, "Why _channel is empty?");
                        continue;
                    } else {
                        LiveEventBus.get()
                                .with(KEY_TEST_OBSERVE)
                                .post(ByteArrayToHexString(_channel.getSelectResponse()));
                        Log.i(TAG, "open channel ok, response = " + ByteArrayToHexString(_channel.getSelectResponse()));
                    }
                }
                if ((commandApdu[1] == (byte)0xa4)/* && (commandApdu[4] == (byte)0x06)*/) {
                    Log.i(TAG, "Selection already processed on above step.");
                } else {
                    try {
                        Log.i(TAG, "process command = " + ByteArrayToHexString(commandApdu));
                        LiveEventBus.get()
                                .with(KEY_TEST_OBSERVE)
                                .post(ByteArrayToHexString(commandApdu));
                        final byte[] rsp = _channel.transmit(commandApdu);
                        Log.i(TAG, "process response = " + ByteArrayToHexString(rsp));
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Log.i(TAG, "process sendResponseApdu rsp = " + ByteArrayToHexString(rsp));
                                sendResponseApdu(rsp);
                            }
                        });
                        LiveEventBus.get()
                                .with(KEY_TEST_OBSERVE)
                                .post(ByteArrayToHexString(rsp));
                    } catch (Exception arg6) {
                        arg6.printStackTrace();
                    }
                }
                break;
            }
        }
    }

    /**
     * Build APDU for SELECT AID command. This command indicates which service a reader is
     * interested in communicating with. See ISO 7816-4.
     *
     * @param aid Application ID (AID) to select
     * @return APDU for SELECT AID command
     */
    public static byte[] BuildSelectApdu(String aid) {
        // Format: [CLASS | INSTRUCTION | PARAMETER 1 | PARAMETER 2 | LENGTH | DATA]
        return HexStringToByteArray(SELECT_APDU_HEADER + String.format("%02X", aid.length() / 2) + aid);
    }

    /**
     * Build APDU for GET_DATA command. See ISO 7816-4.
     *
     * @return APDU for SELECT AID command
     */
    public static byte[] BuildGetDataApdu() {
        // Format: [CLASS | INSTRUCTION | PARAMETER 1 | PARAMETER 2 | LENGTH | DATA]
        return HexStringToByteArray(GET_DATA_APDU_HEADER + "0FFF");
    }

    /**
     * Utility method to convert a byte array to a hexadecimal string.
     *
     * @param bytes Bytes to convert
     * @return String, containing hexadecimal representation.
     */
    public static String ByteArrayToHexString(byte[] bytes) {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2]; // Each byte has two hex characters (nibbles)
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF; // Cast bytes[j] to int, treating as unsigned value
            hexChars[j * 2] = hexArray[v >>> 4]; // Select hex character from upper nibble
            hexChars[j * 2 + 1] = hexArray[v & 0x0F]; // Select hex character from lower nibble
        }
        return new String(hexChars);
    }

    /**
     * Utility method to convert a hexadecimal string to a byte string.
     *
     * <p>Behavior with input strings containing non-hexadecimal characters is undefined.
     *
     * @param s String containing hexadecimal characters to convert
     * @return Byte array generated from input
     * @throws java.lang.IllegalArgumentException if input length is incorrect
     */
    public static byte[] HexStringToByteArray(String s) throws IllegalArgumentException {
        int len = s.length();
        if (len % 2 == 1) {
            throw new IllegalArgumentException("Hex string must have even number of characters");
        }
        byte[] data = new byte[len / 2]; // Allocate 1 byte per 2 hex characters
        for (int i = 0; i < len; i += 2) {
            // Convert each character into a integer (base-16), then bit-shift into place
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * Utility method to concatenate two byte arrays.
     * @param first First array
     * @param rest Any remaining arrays
     * @return Concatenated copy of input arrays
     */
    public static byte[] ConcatArrays(byte[] first, byte[]... rest) {
        int totalLength = first.length;
        for (byte[] array : rest) {
            totalLength += array.length;
        }
        byte[] result = Arrays.copyOf(first, totalLength);
        int offset = first.length;
        for (byte[] array : rest) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

}
