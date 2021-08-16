package com.example.wristband_supportive_application;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.nfc.Tag;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    //todo: change the UI
    //todo: stop the plotting when the danger zone has been entered
    private static final String TAG = "MyApp";

    // SMS instance
    SmsManager smsManager;
    private static final int SMS_PER = 1;

    // handling the location services
    FusedLocationProviderClient fusedLocationProviderClient;
    // location request is a config file to work with fusedLocationProviderClient
    LocationRequest locationRequest;
    // the last known address of the user
    String userAddress;

    private static final int GPS_INTERVAL = 5000;
    private static final int GPS_LOCATION_PER = 99;

    // predefined values for natural oxygen and heart rate values
    // for individuals around 60 years of age
    private int spo2MIN = 95;
    private int hrMIN = 70;
    private int spo2MAX = 100;
    private int hrMAX = 115;

    Button btnListen, btnDevice, btnStart, btnFinish, btnGPS;
    EditText etPhoneNumber;
    TextView tvStatus, tvData;
    ListView lvPairedDevices;
    RadioGroup rgModeSelection;
    RadioButton rbHR, rbSPO2;

    BluetoothAdapter bluetoothAdapter;
    BluetoothDevice[] btArray;

    SendReceive sendReceive;

    Boolean connectionFlag = false;

    // selected mode from HR and SPO2 ('h' for HR and 's' for SPO2)
    // default set to HR
    char selectedMode = 'h';

    // the number of samples to gather when user enters the perilous zone
    int maxSampleCheck = 20;
    int dangerZoneDataSum = 0;
    int dangerZoneDataCnt = 0;

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

        // setting the properties of LocationRequest
        locationRequest = new LocationRequest();
        // refreshing the location every 5 seconds if we use this app to track, which we don't.
        locationRequest.setFastestInterval(GPS_INTERVAL);
        locationRequest.setPriority(locationRequest.PRIORITY_HIGH_ACCURACY);
        //updateGPS();

        smsManager = SmsManager.getDefault();

    } // end of onCreate method

    // after permissions have been granted, this functions is triggered
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode){
            case GPS_LOCATION_PER:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    updateGPS();
                    Log.e(TAG, "fine");
                }
                else{
                    Toast.makeText(this, "This application requires gps access!!", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
            default:
                break;
        }
    }

    private String updateGPS(){
        // getting the permissions from user
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(MainActivity.this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED){
            // user provided the permission
            fusedLocationProviderClient.getLastLocation().addOnSuccessListener(this, new OnSuccessListener<Location>() {
                @RequiresApi(api = Build.VERSION_CODES.M)
                @Override
                public void onSuccess(Location location) {
                    // getting the actual location to send via SMS
                    Geocoder geocoder = new Geocoder(MainActivity.this);
                    try{
                        List<Address> addressList = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                        userAddress = addressList.get(0).getAddressLine(0);
                        //Toast.makeText(MainActivity.this, userAddress, Toast.LENGTH_LONG).show();

                        // todo: remove this section and move it automatic in the dangerZoneCheck
                        if(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED){
                            try{
                                String phoneNumber = etPhoneNumber.getText().toString().trim();
                                String messageToSend = userAddress.trim();
                                if (!messageToSend.equals("")){
                                    smsManager.sendTextMessage(phoneNumber, null, messageToSend, null, null);
                                    Toast.makeText(MainActivity.this, "Message is sent", Toast.LENGTH_LONG).show();
                                }
                            }catch (Exception e){
                                Toast.makeText(MainActivity.this, "Failed to send message", Toast.LENGTH_LONG).show();
                                e.printStackTrace();
                            }
                        }
                    }catch (Exception e){
                        Toast.makeText(MainActivity.this, "Couldn't get the address!", Toast.LENGTH_SHORT).show();
                        e.printStackTrace();
                    }
                }
            });
        }
        else{
            // user denied the permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, GPS_LOCATION_PER);
            }
        }
        return userAddress;
    }

    private void findViewByID(){
        btnListen = (Button) findViewById(R.id.btn_listen);
        btnDevice = (Button) findViewById(R.id.btn_device);
        btnStart = (Button) findViewById(R.id.btn_start);
        btnFinish = (Button) findViewById(R.id.btn_finish);
        btnGPS = (Button) findViewById(R.id.btn_gps);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvData = (TextView) findViewById(R.id.tv_inputDataExhibition);
        lvPairedDevices = (ListView) findViewById(R.id.lv_pairedDevices);
        rgModeSelection = (RadioGroup) findViewById(R.id.rg_modeSelection);
        rbHR = (RadioButton) findViewById(R.id.rb_HR);
        rbSPO2 = (RadioButton) findViewById(R.id.rb_SPO2);
        etPhoneNumber = (EditText) findViewById(R.id.et_phoneNumber);
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

        btnGPS.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateGPS();
            }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectionFlag = true;
                switch (rgModeSelection.getCheckedRadioButtonId()){
                    case R.id.rb_HR:
                        selectedMode = 'h';
                        sendReceive.write("h".getBytes());
                        break;
                    case R.id.rb_SPO2:
                        selectedMode = 's';
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
                    try{
                        int _msgInt = Integer.parseInt(tempMsg);
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
                            viewport.setMaxY(300);
                            // removing the last 100 points from the chart
                            viewport.setMinX(pointsPlotted - 100);

                            // checking the safe zone for HR and spo2 level
                            check_HR_SPO2(_msgInt);

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
            byte[] buffer = new byte[3];
            int bytes = 0;
//            DataInputStream dataInputStream = new DataInputStream(inputStream);
//            String data;
//            int dataLength;

            while (true)
            {
                try {
                    if(connectionFlag){
//                        data = dataInputStream.readUTF();
                        bytes = inputStream.read(buffer);
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

    private void check_HR_SPO2(int data){
        //heart rate has been selected
        if(selectedMode == 'h') {
            // heart rate exceeds the natural level
            if (data > hrMAX) {
                dangerZoneDataCnt++;
                if (dangerZoneDataCnt < maxSampleCheck) {
                    dangerZoneDataSum += data;
                    dangerZoneDataCnt = 0;
                }
                if (dangerZoneDataSum > hrMAX * maxSampleCheck) {
                    // todo: retrieve the LOCATION and send SMS
                    //updateGPS();
                }
            }

            // heart rate is less than natural level minimum
            else if (data < hrMIN) {
                dangerZoneDataCnt++;
                if (dangerZoneDataCnt < maxSampleCheck) {
                    dangerZoneDataSum += data;
                }
                if (dangerZoneDataSum < hrMIN * maxSampleCheck) {
                    // todo: retrieve the LOCATION and send SMS
                    //updateGPS();
                }
            }
        }
            // oxygen level has been selected
            else if(selectedMode == 's'){
                // oxygen level exceeds the natural level
                if(data > spo2MAX){
                    dangerZoneDataCnt++;
                    if (dangerZoneDataCnt < maxSampleCheck){
                        dangerZoneDataSum += data;
                        dangerZoneDataCnt = 0;
                    }
                    if (dangerZoneDataSum > spo2MAX * maxSampleCheck){
                        // todo: retrieve the LOCATION and send SMS
                        //updateGPS();
                    }
                }

                // oxygen level is less than natural level minimum
                else if(data < spo2MIN) {
                    dangerZoneDataCnt++;
                    if (dangerZoneDataCnt < maxSampleCheck) {
                        dangerZoneDataSum += data;
                    }
                    if (dangerZoneDataSum < spo2MIN * maxSampleCheck) {
                        // todo: retrieve the LOCATION and send SMS
                        //updateGPS();
                    }
                }
        }
    }

}