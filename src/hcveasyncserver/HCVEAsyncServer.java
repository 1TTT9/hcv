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
//import org.jboss.netty.channel.ChannelPipelineCoverage;  ### depreciated on 3.6
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelLocal;
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
    private boolean readLength;
    private int length;
    
    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel handler, ChannelBuffer buffer, VoidEnum state)
    {
        ChannelBuffer buf;
        if (!readLength){
            length = buffer.readInt();
            readLength = true;
            checkpoint();
        }
        
        if (readLength){
            buf = buffer.readBytes(length);
            readLength = false;
            checkpoint();
        }
        return buf;
    }
}

final class ChannelState{
    static final ChannelLocal<Boolean> loginIn = new ChannelLocal<Boolean>(){
        protected Boolean initialValue(Channel ch){
            return false;
        }
    };
    
    static final ChannelLocal<COMMANDSTATE> command = new ChannelLocal<COMMANDSTATE>(){
        protected COMMANDSTATE initialValue(Channel ch){
            return COMMANDSTATE.INIT;
        }
    };
    
    static final ChannelLocal<Integer> sn = new ChannelLocal<Integer>(){
        protected Integer initialValue(Channel ch){
            return 0;
        }
    };
}


//ChannelPipelineCoverage is used to acclaim its availablity for other channels or channelpipelines
//'one' means no longer accessed by other channels
//@ChannelPipelineCoverage("one")  ### depreciated
@Sharable
class ServerHandler extends SimpleChannelHandler{
    private final ChannelBuffer buf = dynamicBuffer();
    
    final static ChannelBuffer syncanswer = buffer(1);
    
    private final int bufsize = 10;
    
    private static final Logger logger = Logger.getLogger(ServerHandler.class.getName());
    
    // is any message coming
    private final AtomicInteger isAnyMessage = new AtomicInteger(0);
    
    // bytes monitor
    private static final AtomicLong transferredBytes = new AtomicLong();
    
    public ServerHandler()
    {
        syncanswer.writeBytes("a".getBytes());
    }
    
    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception
    {
        /*
        String host = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getAddress().getHostAddress();
        int iPort = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getPort();
        clientaddr = host + ':' + Integer.toString(iPort);
        */
        ChannelState.loginIn.set(ctx.getChannel(), true);
        HCVEAsyncServer.channelgroup.add(ctx.getChannel());
        ChannelState.command.set(ctx.getChannel(), COMMANDSTATE.INIT);
        logger.log(Level.INFO, "Connected from " + ctx.getChannel().toString() + ", now #channelgroup: " + Integer.toString(HCVEAsyncServer.channelgroup.size()));
    }
    
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
    {
        switch (Monitor.command)
        {
            case INIT:
                buf.writeBytes((ChannelBuffer)e.getMessage());
                System.out.println(buf.toByteBuffer());
                System.out.println( ctx.getChannel().toString() + " sent");
                e.getChannel().write(syncanswer);
                buf.clear();
                break;
            case ALLREAD:
                if (ChannelState.command.get(ctx.getChannel()) == COMMANDSTATE.WAITWRITE)
                {   
                    break;
                }
                
                if (buf.readableBytes() < bufsize) 
                {
                    buf.writeBytes((ChannelBuffer)e.getMessage());
                }
                else
                {
                    int sn = ChannelState.sn.get(ctx.getChannel());
                    if ( sn != Monitor.sn){
                        System.out.println("Client " + ctx.getChannel().toString() + " delays... SN="+ Integer.toString(sn) 
                                + "current SN=" + Integer.toString(Monitor.sn));
                    }
                    
                    //Monitor.messageSet.get(Monitor.sn).add(buf);
                    ChannelState.sn.set(ctx.getChannel(), (sn+1)%Monitor.maxSN);
                    ChannelState.command.set(ctx.getChannel(), COMMANDSTATE.WAITWRITE);
                }
                break;
            case ALLWRITE:
                if (ChannelState.command.get(ctx.getChannel())  == COMMANDSTATE.WAITREAD)
                { 
                    break; 
                }
                
                e.getChannel().write(buf);
                buf.clear();
                ChannelState.command.set(ctx.getChannel(), COMMANDSTATE.WAITREAD);
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
    
    private static final ServerHandler SHARED = new ServerHandler();    
    
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
        return Channels.pipeline(new MessageDecoder(), SHARED);
    }
}


class Monitor extends Thread
{
    static final int maxSN = 10*2;
    static int sn = 0;
    static COMMANDSTATE command = COMMANDSTATE.INIT;
    
    public Monitor()
    {}    
    
    public boolean isARC()
    {
        if (HCVEAsyncServer.messageSet.get(sn).size() < HCVEAsyncServer.channelgroup.size())
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

                    synchronized(HCVEAsyncServer.messageSet)
                    {
                        Iterator i = HCVEAsyncServer.messageSet.get(sn).iterator();
                        while (i.hasNext())
                        {
                            ChannelBuffer cb = (ChannelBuffer)i.next();
                            System.out.println("Receive data ... " + cb);
                        }
                    };
                    command =  COMMANDSTATE.ALLWRITE;
                    sn = (sn+1)%maxSN;
                    HCVEAsyncServer.messageSet.get(Math.abs(sn-maxSN/2)).clear();
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
    static List<ArrayList<ChannelBuffer>> messageSet;
    
    public HCVEAsyncServer()
    {}
    
    public void run()
    {
        messageSet = Collections.synchronizedList(new ArrayList<ArrayList<ChannelBuffer>>());
        for (int i=0;i<Monitor.maxSN;i++){
            messageSet.add(new ArrayList<ChannelBuffer>());
        };

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



