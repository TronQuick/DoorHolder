package com.giri.fridgev2;

import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.giri.fridgev2.entity.Data;
import com.giri.fridgev2.utils.PostStringUtil;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    /**
     * 初始化【通过机会】
     */
    int passChance = 0;

    // 创建线程
    Thread mainThread = new MainThread();
    Thread passStatusThread = new PassStatusThread();
    Thread doorStatusThread = new DoorStatusThread();
    Thread cleanChanceThread = new CleanChanceThread();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 这段代码用于解决Android 4.0 之后不能在主线程中请求HTTP请求的问题
        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }

        // 开启主线程
        mainThread.start();

        // 开启线程2
        passStatusThread.start();

        // 开启线程3
        doorStatusThread.start();

        // 开启线程4
        cleanChanceThread.start();
    }

    /**
     * 线程1：监控【通过机会】，异常时发送异常请求
     */
    private class MainThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    /** 睡眠（延时）0.1秒后执行 */
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (passChance < 0) {
                    Log.d("HM", "通过次数异常，目前通过机会为:" + passChance);
                    // 发送异常情况到云端
                    postString();

                    // 蜂鸣报警

                    while (passChance < 0) {
                        // 暂停线程1
                    }
                }
            }
        }

    }

    /**
     * 线程2：从【虹膜识别仪器】接收【识别成功信号】，
     * 增加【通过机会】
     */
    private class PassStatusThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    /** 睡眠（延时）0.1秒后执行 */
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                String passStatus = getPassStatus();
                if (passStatus.equals("1")) {
                    passChance += 1;
                    Log.d("HM", "虹膜识别成功，目前通过机会为:" + passChance);
                    try {
                        /** 睡眠（延时）4秒后执行 */
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            }
        }

    }

    /**
     * 线程3：接收【红外感应器】发来的【人员通过信号】，扣除【通过机会】
     */
    private class DoorStatusThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    /** 睡眠（延时）0.1秒后执行 */
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                String doorStatus;
                doorStatus = getDoorStatus();
                if (doorStatus.equals("1")) {
                    passChance -= 1;
                    Log.d("HM", "识别到人员通过，目前通过机会为:" + passChance);
                }
                while (doorStatus.equals("1")) {
                    doorStatus = getDoorStatus();
                }
            }
        }

    }

    /**
     * 线程4：十秒内【通过机会】无发生改变，【通过机会】清零
     */
    private class CleanChanceThread extends Thread {
        @Override
        public void run() {
            while (true) {
                int count = 0;
                int chanceCount = passChance;
                while (chanceCount == passChance) {
                    try {
                        /** 睡眠（延时）1秒后执行 */
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    count++;
                    if (count >= 10) {
                        count = 0;
                        passChance = 0;
                        Log.d("HM", "十秒无人通过，清零，目前通过机会为:" + passChance);
                    }
                }
            }
        }

    }


    /**
     * GPIO获取限位开关的输出值,得到柜门状态（1/0）
     *
     * @return 柜门状态:String "1" / "0"
     * <p>
     * 端口-输出值：
     * 经推算，若需要读取【端口n】的输出值【buffer[i]】,那么
     * i = ( n + 11 ) * 13 + 7
     * <p>
     * J303-3：65    buffer[995]  *目前作为左
     * J303-4：68    buffer[1034]
     * J303-5：66    buffer[1008]
     * <p>
     * J602-1: 81    buffer[1203] *目前作为右
     * J602-2: 80    buffer[1190]
     */

    /**
     * 获取虹膜识别仪器信号
     */
    private String getPassStatus() {
        String passStatus = "0";

        // 定义路径
        String gpioPath = "/sys/devices/virtual/misc/mtgpio/pin";

        // 创建接收缓冲区
        char[] buffer = new char[2048];

        try {
            @SuppressWarnings("resource")
            FileReader fileReader = new FileReader(gpioPath);
            BufferedReader reader = new BufferedReader(fileReader);
            reader.read(buffer);

            passStatus = buffer[995] + "";
            reader.close();
            fileReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return passStatus;
    }

    /**
     * 从感应门获取信号
     */
    private String getDoorStatus() {
        String doorStatus = "0";

        // 定义路径
        String gpioPath = "/sys/devices/virtual/misc/mtgpio/pin";

        // 创建接收缓冲区
        char[] buffer = new char[2048];

        try {
            @SuppressWarnings("resource")
            FileReader fileReader = new FileReader(gpioPath);
            BufferedReader reader = new BufferedReader(fileReader);
            reader.read(buffer);

            doorStatus = buffer[995] + "";
            reader.close();
            fileReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return doorStatus;
    }

    private void postString() {

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        String uploadJSON = "{\n" +
                " \"token\":\"934c65904fb224bd25857b466326f72f1b78d78a63f8fc\",\n" +
                " \"data\":{\n" +
                "  \"tn\":\"tmmt_trailing_info\",\n" +
                "  \"insertObject\":{\n" +
                "   \"device_id\": 2,\n" +
                "   \"trailing_signal\" : 1,\n" +
                "   \"t\":\""+time+"\",\n" +
                "   \"creator\":\"admin\",\n" +
                "   \"create_time\" : \""+time+"\",\n" +
                "   \"modifier\":\"admin\",\n" +
                "   \"modified_time\":\""+time+"\"\n" +
                "  }\n" +
                " }\n" +
                "}";
        Log.d("HMDATA", uploadJSON);

        // 上传uploadImageJSON,调用封装好okHttp方法
        String postURL = getResources().getString(R.string.postURL);
        try {
            PostStringUtil.post(postURL, uploadJSON);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}


