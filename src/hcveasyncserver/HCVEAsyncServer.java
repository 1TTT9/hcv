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
//import org.jboss.netty.channel.Channels;
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
import static org.jboss.netty.channel.Channels.*;

import org.jboss.netty.handler.codec.replay.*;

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import java.lang.InterruptedException;
import java.lang.Thread;
import org.jboss.netty.channel.Channels;


enum COMMANDSTATE{
    INIT, SYNCREAD, SYNCWRITE, WAITREAD, WAITWRITE, ALLREAD, ALLWRITE,
}


class MessageDecoder extends ReplayingDecoder<VoidEnum>{
    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel handler, ChannelBuffer buffer, VoidEnum state)
    {
        return buffer.readBytes(4);
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
    
    public ServerHandler(Monitor monitor)
    {
        this.monitor = monitor;
        this.syncanswer.writeBytes("a".getBytes());
    }
    
    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception
    {
        String host = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getAddress().getHostAddress();
        int iPort = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getPort();
        clientaddr = host + ':' + Integer.toString(iPort);
        
        channelgroup.add(ctx.getChannel());
        channelstate = COMMANDSTATE.SYNCREAD;
        logger.log(Level.INFO, "Connected from " + clientaddr + ", now #channelgroup: " + Integer.toString(channelgroup.size()));
    }
    
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
    {
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
    private Monitor monitor;
    private String answer;
    
    public ServerPipelineFactory(Monitor monitor, String answer)
    {
        super();
        this.monitor = monitor;
        this.answer = answer;
    }
    
    public ChannelPipeline getPipeline() throws Exception
    {
        if (HCVEAsyncServer.iMaxNumOfConn == 0 || HCVEAsyncServer.channelgroup.size() >= HCVEAsyncServer.iMaxNumOfConn)
        {
            throw new Exception("no connection allowed(maximum="+ Integer.toString(HCVEAsyncServer.iMaxNumOfConn) +").");
        }
        /*
        ChannelPipeline pipeline = Channels.pipeline();
        ServerHandler hdl = new ServerHandler(channelgroup, monitor);
        pipeline.addLast("handler", hdl);
        logger.log(Level.INFO, "New comming, now #channelgroup: " + Integer.toString(channelgroup.size())); 
        return pipeline;
        */
        return Channels.pipeline(new MessageDecoder(), new ServerHandler(monitor));
    }
}


class Monitor extends Thread
{
    static final int maxSN = 10*2;
    
    public COMMANDSTATE command = COMMANDSTATE.INIT;
    public List<ArrayList<ChannelBuffer>> messageSet = null;
    public int sn = 0;
    
    public Monitor()
    {
        messageSet = Collections.synchronizedList(new ArrayList<ArrayList<ChannelBuffer>>());
        for (int i=0;i<maxSN;i++)
        {
            messageSet.add(new ArrayList<ChannelBuffer>());
        }
    }    
    
    public boolean isARC()
    {
        if (messageSet.get(sn).size() < HCVEAsyncServer.channelgroup.size())
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
    //ChannelGroup constrcut requires name of the group as a parameter.
    static final ChannelGroup channelgroup = new DefaultChannelGroup(HCVEAsyncServer.class.getName());    
    static final int iPort = 7788;
    static final int iMaxNumOfConn = 2;
    
    public HCVEAsyncServer()
    {}
    
    public void run()
    {
        // monitor
        Monitor monitor = new Monitor();
        monitor.start();
        System.out.println("Monitor starts.");
        
        // boostrap
        ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(), Executors.newCachedThreadPool()
        ));

        //server factory
        ServerPipelineFactory factory = new ServerPipelineFactory(monitor, null);

        bootstrap.setPipelineFactory(factory);        
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setOption("child.keepAlive", true);
        //bootstrap.setOption("reusedAddress", true);
        
        
        Channel serverChannel = bootstrap.bind(new InetSocketAddress(iPort));
        channelgroup.add(serverChannel);
        System.out.println("port binds.");
        channelgroup.close().awaitUninterruptibly();
        System.out.println("server starts.");   
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // TODO code application logic here
      
        /*
        boolean isParamsAllowed = false;
        if (isParamsAllowed) 
        {
            if (args.length == 2)
            {
                this.iPort = Integer.parseInt(args[0]);
                iMaxNumConn = Integer.parseInt(args[1]);
            }
            else
            {
                System.out.println("Usage: " + HCVEAsyncServer.class.getName()+ "<port> <MaximumNumOfConnectionsAllowed>");
                return;
            }            
        }
        */
        new HCVEAsyncServer().run();
    }
}



