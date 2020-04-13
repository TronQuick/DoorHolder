package com.giri.fridgev2;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.giri.fridgev2.utils.PostStringUtil;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    /** 音效 */
    // 获取资源文件
    AssetManager assetManager;

    //实例化SoundPool
    SoundPool soundPool;

    /**
     * 初始化【通过机会】
     */
    int passChance = 0;

    String doorStatus = "1";

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
        if (Build.VERSION.SDK_INT > 9) {
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
            assetManager = getAssets();
            while (true) {
                try {
                    /** 睡眠（延时）0.1秒后执行 */
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (passChance < 0) {
                    Log.d("HM", "通过次数异常，目前通过机会为:" + passChance);

                    // 播放警告音效
                    try {
                        playWarningSound();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // 发送异常情况到云端
                    postErrorString();

                    // 等待音效播放完成
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    /** release SoundPool*/
                    releaseSoundpool();

                    // 循环
                    int flag = passChance;
                    while (flag < 0) {
                        // 暂停线程1
                        flag = passChance;
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
                    /** 睡眠（延时）0.05秒后执行 */
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                String passStatus = getPassStatus();
                if (passStatus.equals("1")) {
                    passChance += 1;
                    Log.d("HM", "虹膜识别成功，目前通过机会为:" + passChance);
                    while (passStatus.equals("1")) {
                        passStatus = getPassStatus();
                    }
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
                getDoorStatus();
                if (doorStatus.equals("0")) {
                    passChance -= 1;
                    Log.d("HM", "识别到人员通过，目前通过机会为:" + passChance);
                    while (doorStatus.equals("0")) {
                        getDoorStatus();
                    }
                    try {
                        /** 睡眠（延时）0.5秒后执行 */
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
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
                while (chanceCount == passChance && doorStatus.equals("1") && getPassStatus().equals("0") && passChance != 0) {

                    try {
                        /** 睡眠（延时）0.1秒后执行 */
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    count++;
                    if (count >= 100) {
                        count = 0;
                        passChance = 0;
                        postZeroString();
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
//            Log.d("GPIO","J303:" + passStatus);
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
    private void getDoorStatus() {

        // 定义路径
        String gpioPath = "/sys/devices/virtual/misc/mtgpio/pin";

        // 创建接收缓冲区
        char[] buffer = new char[2048];

        try {
            @SuppressWarnings("resource")
            FileReader fileReader = new FileReader(gpioPath);
            BufferedReader reader = new BufferedReader(fileReader);
            reader.read(buffer);

            doorStatus = buffer[1203] + "";
//            Log.d("GPIO", "J602-1:" + doorStatus);
            reader.close();
            fileReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void postErrorString() {

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        String uploadJSON = "{\n" +
                " \"token\":\"934c65904fb224bd25857b466326f72f1b78d78a63f8fc\",\n" +
                " \"data\":{\n" +
                "  \"tn\":\"tmmt_trailing_info\",\n" +
                "  \"insertObject\":{\n" +
                "   \"device_mac\": \"05ad110110037a\",\n" +
                "   \"trailing_signal\" : 1,\n" +
                "   \"t\":\"" + time + "\",\n" +
                "   \"creator\":\"admin\",\n" +
                "   \"create_time\" : \"" + time + "\",\n" +
                "   \"modifier\":\"admin\",\n" +
                "   \"modified_time\":\"" + time + "\"\n" +
                "  }\n" +
                " }\n" +
                "}";
        Log.d("DATA", uploadJSON);

        // 上传uploadImageJSON,调用封装好okHttp方法
        String postURL = getResources().getString(R.string.postURL);
        try {
            PostStringUtil.post(postURL, uploadJSON);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void postZeroString() {

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        String uploadJSON = "{\n" +
                " \"token\":\"934c65904fb224bd25857b466326f72f1b78d78a63f8fc\",\n" +
                " \"data\":{\n" +
                "  \"tn\":\"tmmt_trailing_info\",\n" +
                "  \"insertObject\":{\n" +
                "   \"device_mac\": \"05ad110110037a\",\n" +
                "   \"trailing_signal\" : 0,\n" +
                "   \"t\":\"" + time + "\",\n" +
                "   \"creator\":\"admin\",\n" +
                "   \"create_time\" : \"" + time + "\",\n" +
                "   \"modifier\":\"admin\",\n" +
                "   \"modified_time\":\"" + time + "\"\n" +
                "  }\n" +
                " }\n" +
                "}";
        Log.d("DATA", uploadJSON);

        // 上传uploadImageJSON,调用封装好okHttp方法
        String postURL = getResources().getString(R.string.postURL);
        try {
            PostStringUtil.post(postURL, uploadJSON);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void playWarningSound() throws IOException {

        //sdk版本21是SoundPool 的一个分水岭
        if(Build.VERSION.SDK_INT >= 21) {
            SoundPool.Builder builder = new SoundPool.Builder();
            //传入最多播放音频数量,
            builder.setMaxStreams(1);
            //AudioAttributes是一个封装音频各种属性的方法
            AudioAttributes.Builder attrBuilder = new AudioAttributes.Builder();
            //设置音频流的合适的属性
            attrBuilder.setLegacyStreamType(AudioManager.STREAM_MUSIC);
            //加载一个AudioAttributes
            builder.setAudioAttributes(attrBuilder.build());
            soundPool = builder.build();
        }
        else {
            /**
             * 第一个参数：int maxStreams：SoundPool对象的最大并发流数
             * 第二个参数：int streamType：AudioManager中描述的音频流类型
             *第三个参数：int srcQuality：采样率转换器的质量。 目前没有效果。 使用0作为默认值。
             */
            soundPool = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
        }

        AssetFileDescriptor horn = assetManager.openFd("WarningSound.mp3");

        final int voice = soundPool.load(horn, 1);

        //异步需要等待加载完成，音频才能播放成功
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if (status == 0) {
                    //第一个参数soundID
                    //第二个参数leftVolume为左侧音量值（范围= 0.0到1.0）
                    //第三个参数rightVolume为右的音量值（范围= 0.0到1.0）
                    //第四个参数priority 为流的优先级，值越大优先级高，影响当同时播放数量超出了最大支持数时SoundPool对该流的处理
                    //第五个参数loop 为音频重复播放次数，0为值播放一次，-1为无限循环，其他值为播放loop+1次
                    //第六个参数 rate为播放的速率，范围0.5-2.0(0.5为一半速率，1.0为正常速率，2.0为两倍速率)
                    soundPool.play(voice, 1, 1, 1, 0, 1);
                }
            }
        });
        horn.close();
    }

    private void releaseSoundpool() {
        soundPool.release();
    }

}


