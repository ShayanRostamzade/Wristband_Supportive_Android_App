package com.example.wristband_supportive_application;

import androidx.appcompat.app.AppCompatActivity;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    //todo: change the UI
    //todo: stop plotting when the finish button is pressed
    //todo: stop the plotting when the danger zone has been entered
    private static final String TAG = "MyApp";

    Button btnListen, btnDevice, btnStart, btnFinish;
    TextView tvStatus, tvData;
    ListView lvPairedDevices;
    RadioGroup rgModeSelection;
    RadioButton rbHR, rbSPO2;

    BluetoothAdapter bluetoothAdapter;
    BluetoothDevice[] btArray;

    SendReceive sendReceive;

    Boolean connectionFlag = false;

    static final int STATE_LISTENING = 1;
    static final int STATE_CONNECTING=2;
    static final int STATE_CONNECTED=3;
    static final int STATE_CONNECTION_FAILED=4;
    static final int STATE_MESSAGE_RECEIVED=5;

    int REQUEST_ENABLE_BLUETOOTH=1;

    private static final String APP_NAME = "Wristband_Supportive_Application";
    private static final UUID MY_UUID=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /*
    Graph property section
     */
    // the number of points plotted on screen considering 5 data points below
    private int pointsPlotted = 5;

    private Viewport viewport;

    // the amount of time between showing the
    private int graphIntervalCounter = 0;

    // the y coordinate is the HR or SPO2 values
    LineGraphSeries<DataPoint> series = new LineGraphSeries<DataPoint>(new DataPoint[] {
            new DataPoint(0, 1),
            new DataPoint(1, 5),
            new DataPoint(2, 3),
            new DataPoint(3, 2),
            new DataPoint(4, 6)
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewByID();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        try {
            if(!bluetoothAdapter.isEnabled())
            {
                Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableIntent,REQUEST_ENABLE_BLUETOOTH);
            }
        }catch (NullPointerException e){
            e.printStackTrace();
        }

        implementListeners();

        // Sample graph code
        GraphView graph = (GraphView) findViewById(R.id.graph);
        viewport = graph.getViewport();
        viewport.setScalable(true);
        viewport.setXAxisBoundsManual(true);
        viewport.setYAxisBoundsManual(true);
        graph.addSeries(series);

    }

    private void findViewByID(){
        btnListen = (Button) findViewById(R.id.btn_listen);
        btnDevice = (Button) findViewById(R.id.btn_device);
        btnStart = (Button) findViewById(R.id.btn_start);
        btnFinish = (Button) findViewById(R.id.btn_finish);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvData = (TextView) findViewById(R.id.tv_inputDataExhibition);
        lvPairedDevices = (ListView) findViewById(R.id.lv_pairedDevices);
        rgModeSelection = (RadioGroup) findViewById(R.id.rg_modeSelection);
        rbHR = (RadioButton) findViewById(R.id.rb_HR);
        rbSPO2 = (RadioButton) findViewById(R.id.rb_SPO2);
    }

    private void implementListeners(){
        btnDevice.setOnClickListener(view -> {
            Set<BluetoothDevice> bt = bluetoothAdapter.getBondedDevices();
            String[] strings = new String[bt.size()];
            btArray = new BluetoothDevice[bt.size()];
            int index = 0;

            if(bt.size() > 0)
            {
                for(BluetoothDevice device : bt)
                {
                    btArray[index] = device;
                    strings[index] = device.getName();
                    index++;
                }
                ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(getApplicationContext(),android.R.layout.simple_list_item_1,strings);
                lvPairedDevices.setAdapter(arrayAdapter);
            }
        });

        btnListen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ServerClass serverClass = new ServerClass();
                serverClass.start();
            }
        });

        lvPairedDevices.setOnItemClickListener((adapterView, view, i, l) -> {
            ClientClass clientClass = new ClientClass(btArray[i]);
            clientClass.start();

            tvStatus.setText("Connecting");
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectionFlag = true;
                switch (rgModeSelection.getCheckedRadioButtonId()){
                    case R.id.rb_HR:
                        sendReceive.write("h".getBytes());
                        break;
                    case R.id.rb_SPO2:
                        sendReceive.write("s".getBytes());
                        break;
                    default:
                        break;
                }
            }
        });

        //todo: implement the finish button functionality
        btnFinish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // disconnect the bluetooth and close the socket
                connectionFlag = false;
            }
        });
    }

    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {

            switch (msg.what)
            {
                case STATE_LISTENING:
                    tvStatus.setText("Listening");
                    break;
                case STATE_CONNECTING:
                    tvStatus.setText("Connecting");
                    break;
                case STATE_CONNECTED:
                    tvStatus.setText("Connected");
                    break;
                case STATE_CONNECTION_FAILED:
                    tvStatus.setText("Connection Failed");
                    break;
                case STATE_MESSAGE_RECEIVED:
                    byte[] readBuff = (byte[]) msg.obj;
                    String tempMsg = new String(readBuff, 0, msg.arg1);
                    // todo: if data is more than 45 then ...
                    char[] temp = new char[tempMsg.length()];
                    char first = 0;
                    char third = 0;
                    char fourth = 0;
                    for (int i = 0; i < tempMsg.length(); i++) {
                        temp[i] = tempMsg.charAt(i);
                        if(i == 0)
                            first = temp[i];
                        if(i == 2)
                            third = temp[i];
                        if(i == 3)
                            fourth = temp[i];
                    }
                    if(first == third){
                        char[] trimmed2 = new char[2];
                        for(int i = 0; i < 2; i++){
                            trimmed2[i] = temp[i];
                        }
                        tempMsg = String.valueOf(trimmed2);
                    }
                    else if(first == fourth){
                        char[] trimmed3 = new char[3];
                        tempMsg = String.valueOf(trimmed3);
                        for(int i = 0; i < 3; i++){
                            trimmed3[i] = temp[i];
                        }
                    }


//                    String _msgInt = (String) msg.obj;
//                    String tempMsg = String.valueOf(_msgInt);
                    try{
                        int _msgInt = Integer.parseInt(tempMsg);
//                        int _msgInt = ByteBuffer.wrap(readBuff).getShort();
                        if(countDigit(_msgInt) >= 2){
                            tvData.setText(tempMsg);

                            //update the graph
                            pointsPlotted++;
                            // resetting the chart if data points are exceeding 5000 samples
                            if(pointsPlotted > 5000){
                                pointsPlotted = 1;
                                series.resetData(new DataPoint[] {new DataPoint(1, 0)});
                            }
                            series.appendData(new DataPoint(pointsPlotted, _msgInt), true, pointsPlotted);
                            viewport.setMaxX(pointsPlotted);
                            viewport.setMaxY(200);
                            // removing the last 200 points from the chart
                            viewport.setMinX(pointsPlotted - 100);
                            Log.e(TAG, "m_data: " + _msgInt);
                        }
                    }catch (NumberFormatException e){
                        e.printStackTrace();
                    }
                    break;
            }
            return true;
        }
    });

    private class ServerClass extends Thread
    {
        private BluetoothServerSocket serverSocket;

        public ServerClass(){
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            BluetoothSocket socket = null;

            while (socket == null)
            {
                try {
                    Message message = Message.obtain();
                    message.what = STATE_CONNECTING;
                    handler.sendMessage(message);

                    socket = serverSocket.accept();
                } catch (IOException e) {
                    e.printStackTrace();
                    Message message = Message.obtain();
                    message.what = STATE_CONNECTION_FAILED;
                    handler.sendMessage(message);
                }

                if(socket!=null)
                {
                    Message message=Message.obtain();
                    message.what=STATE_CONNECTED;
                    handler.sendMessage(message);

                    sendReceive=new SendReceive(socket);
                    sendReceive.start();

                    break;
                }
            }
        }
    }

    private class ClientClass extends Thread
    {
        private BluetoothDevice device;
        private BluetoothSocket socket;

        public ClientClass (BluetoothDevice device1)
        {
            device = device1;

            try {
                socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            try {
                socket.connect();
                Message message = Message.obtain();
                message.what = STATE_CONNECTED;
                handler.sendMessage(message);

                sendReceive = new SendReceive(socket);
                sendReceive.start();

            } catch (IOException e) {
                e.printStackTrace();
                Message message=Message.obtain();
                message.what=STATE_CONNECTION_FAILED;
                handler.sendMessage(message);
            }
        }
    }

    private class SendReceive extends Thread
    {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public SendReceive (BluetoothSocket socket)
        {
            bluetoothSocket = socket;
            InputStream tempIn = null;
            OutputStream tempOut = null;

            try {
                tempIn = bluetoothSocket.getInputStream();
                tempOut = bluetoothSocket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            inputStream = tempIn;
            outputStream = tempOut;
        }

        public void run()
        {
            byte[] buffer = new byte[16];
            int bytes = 0;
//            DataInputStream dataInputStream = new DataInputStream(inputStream);
//            String data;
//            int dataLength;

            while (bytes != -1)
            {
                try {
                    if(connectionFlag){
//                        data = dataInputStream.readUTF();
                        bytes = inputStream.read(buffer, 0, 6);
                        handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        public void write(byte[] bytes)
        {
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    public int countDigit(int n)
    {
        int count = 0;
        while (n != 0) {
            n = n / 10;
            ++count;
        }
        return count;
    }

//    public char[] pattern(String in){
//        int pattern_end = 0;
//        for(int i = pattern_end+1 ; i < in.length(); i++) {
//            int pattern_dex = i % (pattern_end + 1);
//            if (in[pattern_dex] != in[i]) {
//                pattern_end = i;
//                continue;
//            }
//            if (i == in.length - 1) {
//                return in[0:pattern_end + 1];
//
//            }
//        }
//        return in;
//    }

}