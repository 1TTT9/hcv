/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcveasyncserver;


import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.group.ChannelGroup;

import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;


//USED FOR HANDLER
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.logging.Level;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

import static org.jboss.netty.buffer.ChannelBuffers.*;

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import java.lang.InterruptedException;
import java.lang.Thread;


enum COMMANDSTATE{
    INIT, SYNCREAD, SYNCWRITE, WAITREAD, WAITWRITE, ALLREAD, ALLWRITE,
}

class CPP{
    public CPP()
    {}
}

class BBB{
    private int key;
    
    public BBB(int key)
    {
        this.key = key;
    }
}


//ChannelPipelineCoverage is used to acclaim its availablity for other channels or channelpipelines
//'one' means no longer accessed by other channels
@ChannelPipelineCoverage("one")
class ServerHandler extends SimpleChannelHandler{
    
    private int sn = 0;
    
    private String clientaddr;
    
    private COMMANDSTATE channelstate = COMMANDSTATE.INIT;  //0:init, 1:WaitRead(WR) 2:ReadyRead(RR), 3:WaitWrite(WW)  4:ReadyWrite(RW)
    
    private final ChannelBuffer buf = dynamicBuffer();
    
    private ChannelBuffer syncanswer = buffer(1);
    
    private final int bufsize = 10;
    
    private static final Logger logger = Logger.getLogger(ServerHandler.class.getName());
    
    // is any message coming
    private final AtomicInteger isAnyMessage = new AtomicInteger(0);
    
    // bytes monitor
    private static final AtomicLong transferredBytes = new AtomicLong();
        
    private ChannelGroup channelgroup = null;
    
    private Monitor monitor;
    
    public ServerHandler(ChannelGroup channelgroup, Monitor monitor)
    {
        this.channelgroup = channelgroup;
        this.monitor = monitor;
        this.syncanswer.writeBytes("a".getBytes());
    }
    
    public long getTransferredBytes()
    {
        return transferredBytes.get();
    }
    
    
    public ChannelBuffer getBuffer()
    {
        return buf;
    }
    
    public boolean clearBuffer()
    {
        buf.clear();
        return true;
    }
    
    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception
    {
        
        String host = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getAddress().getHostAddress();
        int iPort = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getPort();
        clientaddr = host+':'+Integer.toString(iPort);
        
        channelgroup.add(ctx.getChannel());
        
        channelstate = COMMANDSTATE.SYNCREAD;
        
        logger.log(Level.INFO, "Connected from " + clientaddr + ", now #channelgroup: " + Integer.toString(channelgroup.size()));
    }
    
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
    {
        /*
        transferredBytes.addAndGet(((ChannelBuffer) e.getMessage()).readableBytes());
        e.getChannel().write(e.getMessage());
        */
        
        switch (monitor.command)
        {
            case INIT:
                buf.writeBytes((ChannelBuffer)e.getMessage());
                System.out.println(buf.toByteBuffer());
                System.out.println( clientaddr + " sent");
                e.getChannel().write(syncanswer);
                buf.clear();
                break;
            case ALLREAD:                
                if (channelstate == COMMANDSTATE.WAITWRITE)
                {   
                    break;
                }
                
                if (buf.readableBytes() < bufsize) 
                {
                    buf.writeBytes((ChannelBuffer)e.getMessage());
                }
                else
                {
                    if (monitor.sn != sn)
                    {
                        System.out.println("Client " + clientaddr + " delays... SN="+ Integer.toString(sn) 
                                + "current SN=" + Integer.toString(monitor.sn));
                    }
                    monitor.messageSet.get(monitor.sn).add(buf);
                    sn = (sn+1)%Monitor.maxSN;
                    channelstate = COMMANDSTATE.WAITWRITE;
                }
                break;
            case ALLWRITE:
                if (channelstate == COMMANDSTATE.WAITREAD)
                { 
                    break; 
                }
                
                e.getChannel().write(buf);
                buf.clear();
                channelstate = COMMANDSTATE.WAITREAD;
                break;
        }

    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
    {
        //logger.log(Level.WARNING, "Unexpected expection from downstream", e.getCause());
        e.getChannel().close();
    }
}

class ServerPipelineFactory implements ChannelPipelineFactory{
    private static final Logger logger = Logger.getLogger(ServerPipelineFactory.class.getName());    
    private ChannelGroup channelgroup = null;
    private Monitor monitor = null;
    // private OrderedMemoryAwareThreadPoolExecutor pipelineExecutor = null;
    private String answer = null;
    private int max = 10;
    

    
    public ServerPipelineFactory(ChannelGroup channelgroup, Monitor monitor, 
            String answer, int max)
    {
        super();
        this.channelgroup = channelgroup;
        this.monitor = monitor;
        this.answer = answer;
        this.max = max;

    }
    
    public ChannelPipeline getPipeline() throws Exception
    {
        if (max == 0 || channelgroup.size() >= max)
        {
            throw new Exception("no connection allowed(maximum="+ max +").");
        }
        
        ChannelPipeline pipeline = Channels.pipeline();
        
        ServerHandler hdl = new ServerHandler(channelgroup, monitor);
        pipeline.addLast("handler", hdl);

        logger.log(Level.INFO, "New comming, now #channelgroup: " + Integer.toString(channelgroup.size())); 
        return pipeline;
    }
}


class Monitor extends Thread
{
    public static int maxSN = 10*2;
    
    public COMMANDSTATE command = COMMANDSTATE.INIT;
    public List<ArrayList<ChannelBuffer>> messageSet = null;
    public int sn = 0;
    
    private ChannelGroup channelgroup = null;

    
    public Monitor(ChannelGroup channelgroup)
    {
        this.channelgroup = channelgroup;
        
        messageSet = Collections.synchronizedList(new ArrayList<ArrayList<ChannelBuffer>>());        
        for (int i=0;i<maxSN;i++)
        {
            messageSet.add(new ArrayList<ChannelBuffer>());
        }
    }    
    
    public boolean isARC()
    {
        if (messageSet.get(sn).size() < channelgroup.size())
        {
            return false;
        }
        return true;
    }
    
    @Override
    public void run()
    {
        try{
            
            boolean readyMonitored = false;            
            
            for (;;)
            {
                Thread.sleep(1500);
//                System.out.println("monitoring...");
                if (readyMonitored){
                    command = COMMANDSTATE.ALLREAD;
                    Thread.sleep(1000);
                    if (!isARC())
                    {
                        System.out.println("Ohh... Somebody delays...");
                    }

                    synchronized(messageSet)
                    {
                        Iterator i = messageSet.get(sn).iterator();
                        while (i.hasNext())
                        {
                            ChannelBuffer cb = (ChannelBuffer)i.next();
                            System.out.println("Receive data ... " + cb);
                        }
                    };
                    command =  COMMANDSTATE.ALLWRITE;
                    sn = (sn+1)%maxSN;
                    messageSet.get(Math.abs(sn-maxSN/2)).clear();
                }
            }
            
        } catch (InterruptedException e){
        }
    }
}


/**
 *
 * @author demo
 */
public class HCVEAsyncServer {

    private final int iPort;
    private final int iMaxNumOfConn;
    
    public HCVEAsyncServer(int iPort, int iMaxNumOfConn)
    {
        this.iPort = iPort;
        this.iMaxNumOfConn = iMaxNumOfConn;
    }
    
    public void run()
    {
        ChannelGroup channelgroup = new DefaultChannelGroup(HCVEAsyncServer.class.getName());
        
        Monitor monitor = new Monitor(channelgroup);

        monitor.start();
        System.out.println("Monitor starts.");
        
        ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(), Executors.newCachedThreadPool()
        ));
        /* [Ping-pong version]
         * new NioServerSocketChannelFactory(
         *   Executors.newCachedThreadPool(), Executors.newCachedThreadPool(), 
         *   Runtime.getRuntime().availableProcessors()*2+1)
         */
        
        /*
        OrderedMemonyAwareThreadPoolExecutor pipelineexecutor = 
                new OrderedMemonyAwareThreadPoolExecutor(
                    200, 1048576, 1073741824, 100, );
        */ 
        
        
        /*
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(new ServerHandler());
            }
        });
        */ 
        ServerPipelineFactory factory = new ServerPipelineFactory(
                channelgroup, monitor, null, iMaxNumOfConn);
        
        bootstrap.setPipelineFactory(factory);
        
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setOption("child.keepAlive", true);
        //bootstrap.setOption("reusedAddress", true);
        
        
        bootstrap.bind(new InetSocketAddress(iPort));
        System.out.println("port binds.");        
        //Channel channel = bootstrap.bind(new InetSocketAddress(iPort));
        //channelgroup.add(channel);
        channelgroup.close().awaitUninterruptibly();
        System.out.println("server starts.");        
    }
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // TODO code application logic here
        int iPort = 7788;
        int iMaxNumConn = 2;
        
        boolean isParamized = false;
        
        if (isParamized) 
        {
            if (args.length == 2)
            {
                iPort = Integer.parseInt(args[0]);
                iMaxNumConn = Integer.parseInt(args[1]);
            }
            else
            {
                System.out.println("Usage: " + HCVEAsyncServer.class.getName()+ "<port> <MaximumNumOfConnectionsAllowed>");
                return;
            }            
        }
        
        new HCVEAsyncServer(iPort, iMaxNumConn).run();
    }
}



