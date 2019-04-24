package com.frankliu.easytransferapp.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.frankliu.easytransferapp.entity.Task;
import com.frankliu.easytransferapp.entity.TaskReceiveFile;
import com.frankliu.easytransferapp.entity.TaskSendFile;
import com.frankliu.easytransferapp.network.FileReceiver;
import com.frankliu.easytransferapp.network.FileReceiverCallback;
import com.frankliu.easytransferapp.network.FileSender;
import com.frankliu.easytransferapp.network.FileSenderCallback;
import com.frankliu.easytransferapp.network.Server;
import com.frankliu.easytransferapp.network.ServerCallback;
import com.frankliu.easytransferapp.protocol.BasicProtocol;
import com.frankliu.easytransferapp.protocol.ErrorCode;
import com.frankliu.easytransferapp.protocol.ProtocolFactory;
import com.frankliu.easytransferapp.utils.Config;
import com.frankliu.easytransferapp.utils.Constant;
import com.frankliu.easytransferapp.utils.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.logging.LogLevel;

public class TaskService extends Service {

    public static final int TASK_ID_POOL_SIZE = 10;
    private final String TAG = TaskService.class.getSimpleName();

    private TaskBinder taskBinder = new TaskBinder();
    private ExecutorService executorService;
    private int[] taskIdPool = new int[TASK_ID_POOL_SIZE];

    private LocalBroadcastManager localBroadcastManager;

    private TaskCallback taskCallback;
    private Handler handler;

    private ArrayList<Task> tasks;
    private Server server;
    private ChannelHandlerContext currCtx;

    public class TaskBinder extends Binder{
        public void addTask(Task task){
            Log.w(TAG, "add task:" + task.toString());
            createTask(task);
        }

        public void removeTask(Task task){

        }
        public void setTaskCallback(TaskCallback taskCallback){
            TaskService.this.taskCallback = taskCallback;
        }
        public ArrayList<Task> getTasks(){
            return tasks;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.w(TAG, "onBind");
        return taskBinder;
    }


    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newCachedThreadPool();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        handler = new Handler();
        tasks = new ArrayList<>();
        startMyServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    private void createTask(Task task){
        int taskId = getNewTaskId();
        if(taskId == -1){
            Log.e(TAG, "there is no valid task id ");
            return;
        }
        task.setTaskId(taskId);
        if(Task.TASK_TYPE_SEND_FILE == task.getTaskType()){
            createFileSendTask((TaskSendFile)task);
        }else if(Task.TASK_TYPE_RECEIVE_FILE == task.getTaskType()){
            createFileReceiveTask((TaskReceiveFile)task);
        }
    }

    private void createFileSendTask(TaskSendFile taskSendFile){
        try{
            InetAddress inetAddress = InetAddress.getByName(taskSendFile.getIp());
            FileSender fileSender = new FileSender(inetAddress, taskSendFile.getPort(), taskSendFile.getFile(), new FileSenderCallback() {
                @Override
                public void currentProgress(int progress) {
                    Intent intent = new Intent();
                    intent.setAction(Constant.ACTION_UPDATE_TASK_PROGRESS);
                    intent.putExtra("taskId", taskSendFile.getTaskId());
                    intent.putExtra("progress", progress);
                    localBroadcastManager.sendBroadcast(intent);
                }

                @Override
                public void finish() {
                    releaseTaskId(taskSendFile.getTaskId());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            taskCallback.taskFinished(taskSendFile);
                        }
                    });
                }
            });
            executorService.execute(fileSender);
            taskCallback.taskReady(taskSendFile);
            tasks.add(taskSendFile);
        }catch (UnknownHostException e){
            e.printStackTrace();
        }


    }

    private void createFileReceiveTask(TaskReceiveFile taskReceiveFile){
        FileReceiver fileReceiver = new FileReceiver(taskReceiveFile.getFileName(), taskReceiveFile.getFileSize(), new FileReceiverCallback() {
            @Override
            public void ready(int port) {
                taskReceiveFile.setReceivePort(port);
                BasicProtocol fileSendResponse = ProtocolFactory.createFileSendResponse(ErrorCode.SUCCESS, port);
                currCtx.writeAndFlush(fileSendResponse);
                Log.w(TAG, "send message FILE_SEND_RESPONSE:" + fileSendResponse.toString());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        taskCallback.taskReady(taskReceiveFile);
                        tasks.add(taskReceiveFile);
                    }
                });
            }

            @Override
            public void currentProgress(int progress) {
                Intent intent = new Intent();
                intent.setAction(Constant.ACTION_UPDATE_TASK_PROGRESS);
                intent.putExtra("taskId", taskReceiveFile.getTaskId());
                intent.putExtra("progress", progress);
                localBroadcastManager.sendBroadcast(intent);
            }

            @Override
            public void finish() {
                releaseTaskId(taskReceiveFile.getTaskId());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        taskCallback.taskFinished(taskReceiveFile);
                    }
                });
            }
        });
        executorService.execute(fileReceiver);
    }
    private void startMyServer(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                int port = Util.getAValidPort();
                Config.FILE_TRANSFER_SERVICE_LISTEN_PORT = port;
                server = new Server(port, new ServerCallback() {
                    @Override
                    public void receiveFile(TaskReceiveFile taskReceiveFile, ChannelHandlerContext ctx) {
                        createTask(taskReceiveFile);
                        currCtx = ctx;
                    }
                });
                server.start();
            }
        }).start();
    }

    private int getNewTaskId(){
        for(int i = 0; i < TASK_ID_POOL_SIZE; i++){
            if(taskIdPool[i] == 0){
                taskIdPool[i] = 1;
                return i;
            }
        }
        return -1;
    }

    private void releaseTaskId(int i){
        if(taskIdPool[i] == 1){
            taskIdPool[i] = 0;
        }else{
            Log.e(TAG, "release task id error");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(server != null){
            server.close();
        }
    }
}
