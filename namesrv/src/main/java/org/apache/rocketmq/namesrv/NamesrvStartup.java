/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.netty.NettySystemConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NamesrvStartup {
    public static Properties properties = null;
    public static CommandLine commandLine = null;

    public static void main(String[] args) {
        main0(args);
    }

    public static NamesrvController main0(String[] args) {
        //设置系统变量：remoting version
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

        if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_SNDBUF_SIZE)) {
            NettySystemConfig.socketSndbufSize = 4096; // 网络 socket 发送缓存区大小， 默认 64k。

        }
        //socket.rcvbuf
        if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_RCVBUF_SIZE)) {
            NettySystemConfig.socketRcvbufSize = 4096;
        }

        try {
            //PackageConflictDetect.detectFastjson();

            Options options = ServerUtil.buildCommandlineOptions(new Options());
            commandLine =
                ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options),
                    new PosixParser());
            if (null == commandLine) {
                System.exit(-1);
                return null;
            }

            final NamesrvConfig namesrvConfig = new NamesrvConfig();
            final NettyServerConfig nettyServerConfig = new NettyServerConfig();
            nettyServerConfig.setListenPort(9876); // namesrv启动监听端口号
            if (commandLine.hasOption('c')) {
                //读取配置的读取配置文件的地址
                String file = commandLine.getOptionValue('c');
                if (file != null) {
                    InputStream in = new BufferedInputStream(new FileInputStream(file));
                    properties = new Properties();
                    properties.load(in);
                    //properties2Object 将properties含有的namesrvConfig，nettyServerConfig需要的配置进行转换
                    MixAll.properties2Object(properties, namesrvConfig);
                    MixAll.properties2Object(properties, nettyServerConfig);

                    namesrvConfig.setConfigStorePath(file);

                    System.out.printf("load config properties file OK, " + file + "%n");
                    in.close();
                }
            }

            if (commandLine.hasOption('p')) {
                MixAll.printObjectProperties(null, namesrvConfig);
                MixAll.printObjectProperties(null, nettyServerConfig);
                System.exit(0);
            }

            MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);

            if (null == namesrvConfig.getRocketmqHome()) {
                System.out.printf("Please set the " + MixAll.ROCKETMQ_HOME_ENV
                    + " variable in your environment to match the location of the RocketMQ installation%n");
                System.exit(-2);
            }

            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(lc);
            lc.reset();
            configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");
            final Logger log = LoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

            MixAll.printObjectProperties(log, namesrvConfig);
            MixAll.printObjectProperties(log, nettyServerConfig);
            //peng NamesrvController启动
            final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);

            // 经配置存储到Controller中
            controller.getConfiguration().registerConfig(properties);
            //peng 舒适化controller
                //(1)从本地加载kvConfig到Manager中
                //(2)启动netty服务
                //(3)封装服务调用执行线程池，并注册服务broker请求处理器DefaultRequestProcessor
                //(4)启动定时任务1：10s钟清理一次
                //todo (5)启动定时任务2：定时KV打印？？？？？
            boolean initResult = controller.initialize();
            if (!initResult) {
                controller.shutdown();
                System.exit(-3);
            }
            // peng 注册 JVM 钩子函数并启动服务器， 以便监昕 Broker 、消息生产者 的网络请求
            //如果代码中使用了线程池，一种优雅停
            //机的方式就是注册一个 JVM 钩子函数， 在 JVM 进程关闭之前，先将线程池关闭 ，及时释
            //放资源 。
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                private volatile boolean hasShutdown = false;
                private AtomicInteger shutdownTimes = new AtomicInteger(0);

                @Override
                public void run() {
                    synchronized (this) {
                        log.info("shutdown hook was invoked, " + this.shutdownTimes.incrementAndGet());
                        if (!this.hasShutdown) {
                            this.hasShutdown = true;
                            long begineTime = System.currentTimeMillis();
                            controller.shutdown();
                            long consumingTimeTotal = System.currentTimeMillis() - begineTime;
                            log.info("shutdown hook over, consuming time total(ms): " + consumingTimeTotal);
                        }
                    }
                }
            }, "ShutdownHook"));
            // peng 启动netty连接
            controller.start();

            String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
            log.info(tip);
            System.out.printf(tip + "%n");

            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Name server config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config item");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }
}
