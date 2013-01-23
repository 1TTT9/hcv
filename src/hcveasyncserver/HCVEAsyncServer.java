/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcveasyncserver;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import java.lang.InterruptedException;
import java.lang.Thread;
import java.io.ByteArrayOutputStream;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import java.nio.ByteBuffer;


import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channel;
//import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
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

import java.text.DecimalFormat;
import org.jboss.netty.channel.ChannelEvent;

enum COMMANDSTATE {

    OFF, INIT, SYNCREAD, SYNCWRITE, WAITREAD, WAITWRITE, ALLREAD, ALLWRITE,
}

final class ChannelState {

    static final ChannelLocal<Boolean> loginIn = new ChannelLocal<Boolean>() {
        protected Boolean initialValue(Channel ch) {
            return false;
        }
    };
    static final ChannelLocal<COMMANDSTATE> command = new ChannelLocal<COMMANDSTATE>() {
        protected COMMANDSTATE initialValue(Channel ch) {
            return COMMANDSTATE.OFF;
        }
    };
    static final ChannelLocal<Integer> sn = new ChannelLocal<Integer>() {
        protected Integer initialValue(Channel ch) {
            return 0;
        }
    };
    /*
     static final ChannelLocal<ByteArrayOutputStream> sb = new ChannelLocal<ByteArrayOutputStream>(){
     protected ByteArrayOutputStream initialValue(Channel ch){
     return new ByteArrayOutputStream();
     }        
     };
     */
    static final ChannelLocal<ArrayList<ByteArrayOutputStream>> messageSet = new ChannelLocal<ArrayList<ByteArrayOutputStream>>() {
        protected ArrayList<ByteArrayOutputStream> initialValue(Channel ch) {
            List<ByteArrayOutputStream> ms = new ArrayList<ByteArrayOutputStream>();
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(new ByteArrayOutputStream());
            };
            return (ArrayList<ByteArrayOutputStream>) ms;
        }
    };
}

class MyData{
    private float data = 3.14f;
    
    public MyData(){
    }
    
    public float getData(){
        return data;
    }
}


class MessageDecoder extends ReplayingDecoder<VoidEnum> {

    private boolean readLength;
    private int length;

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel handler, ChannelBuffer buffer, VoidEnum state) {
        if (!readLength) {
            length = buffer.readInt();
            readLength = true;
            /* checkpoint():
             *   update the initial position of the buffer so that replayingDecoder can rewind the last
             * readerIndex of the buffer to the last position where you called the checkpoint() method
             */
            checkpoint();
        }

        if (readLength) {
            ChannelBuffer buf = buffer.readBytes(length);
            readLength = false;
            checkpoint();
            return buf;
        }
        return null;
    }
}

@Sharable
class ServerHandlerTwo extends SimpleChannelHandler {
    
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        System.out.println("I am testing...");
    }    
    
}

//ChannelPipelineCoverage is used to acclaim its availablity for other channels or channelpipelines
//'one' means no longer accessed by other channels
//@ChannelPipelineCoverage("one")  ### depreciated
@Sharable
class ServerHandler extends SimpleChannelHandler {

    private static final Logger logger = Logger.getLogger(ServerHandler.class.getName());
    // is any message coming
    //private final AtomicInteger isAnyMessage = new AtomicInteger(0);
    // bytes monitor
    //private static final AtomicLong transferredBytes = new AtomicLong();

    public ServerHandler() {
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        /* [remote client's IP and port]
         String host = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getAddress().getHostAddress();
         int iPort = ((InetSocketAddress)ctx.getChannel().getRemoteAddress()).getPort();
         clientaddr = host + ':' + Integer.toString(iPort);
         */
        ChannelState.loginIn.set(e.getChannel(), true);
        HCVEAsyncServer.channelgroup.add(e.getChannel());
        ChannelState.command.set(e.getChannel(), COMMANDSTATE.INIT);
        logger.log(Level.INFO, "Connected from {0}, now #channelgroup: {1}",
                new Object[]{e.getChannel().toString(), HCVEAsyncServer.channelgroup.size()});
        ChannelState.command.set(e.getChannel(), COMMANDSTATE.WAITREAD);
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {        
        if (e.getMessage() instanceof Float)
        {
            ChannelBuffer cb = buffer(4);
            cb.writeFloat((float)e.getMessage());
            write(ctx, e.getFuture(), cb);
           logger.log(Level.INFO, "write float({0}) --- {1}",
                new Object[]{ e.getMessage(), Thread.currentThread().getName()});            
        }else
        {
            super.writeRequested(ctx, e);
        }
    }
    
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        ChannelBuffer buf = (ChannelBuffer) e.getMessage();

        //write-in message 
        int curSN = ChannelState.sn.get(e.getChannel());
        ChannelState.messageSet.get(e.getChannel()).get(curSN).flush();
        ChannelState.messageSet.get(e.getChannel()).get(curSN).write(buf.array());
        ChannelState.sn.set(e.getChannel(), (curSN + 1) % Monitor.maxSN);

        ByteBuffer bb = ByteBuffer.wrap(buf.array());
        DecimalFormat df = new DecimalFormat("+###.##;-###.##");
        logger.log(Level.INFO, "message#{0} from {1}: ({2}, {3}) --- {4}",
                new Object[]{curSN + 1, e.getChannel(), df.format(bb.getFloat()), df.format(bb.getFloat()),
                    Thread.currentThread().getName()});
        bb.flip();
        
        //MyData md = new MyData();
        //ctx.sendDownstream((MessageEvent)md);
        
        /*
         ChannelBuffer cb = buffer(4);
         cb.writeFloat(3.14f);
         //logger.log(Level.INFO, "message sent: {0})", new Object[]{cb});
         e.getChannel().write(cb);
         */

        /*
         switch (Monitor.command)
         {
         case INIT:
         //buf.writeBytes((ChannelBuffer)e.getMessage());
         //System.out.println(buf.toByteBuffer());
         //System.out.println( ctx.getChannel().toString() + " sent");
         e.getChannel().write(syncanswer);
         break;
         case ALLREAD:
         if (ChannelState.command.get(ctx.getChannel()) == COMMANDSTATE.WAITWRITE)
         {   
         break;
         }
                
         if (buf.readableBytes() < 12) 
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
         */
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        //Invoked when a Channel was disconnected from its remote peer.
        logger.log(Level.INFO, "Disconnection from {0}",
                new Object[]{e.getChannel()});
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
        //Invoked when a Channel was closed and all its related resources were released
        logger.log(Level.INFO, "Closed from {0}",
                new Object[]{e.getChannel()});
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        //logger.log(Level.WARNING, "Unexpected expection...", e.getCause());
        e.getChannel().close();
    }
}

class ServerPipelineFactory implements ChannelPipelineFactory {
    private static final Logger logger = Logger.getLogger(ServerPipelineFactory.class.getName());
    private static final ServerHandler SHARED = new ServerHandler();
    private static final ServerHandlerTwo SHAREDTWO = new ServerHandlerTwo();    

    public ServerPipelineFactory() {
        super();
    }

    public ChannelPipeline getPipeline() throws Exception {
        if (HCVEAsyncServer.iMaxNumOfConn == 0 || HCVEAsyncServer.channelgroup.size() >= HCVEAsyncServer.iMaxNumOfConn) {
            throw new Exception("no connection allowed(maximum=" + Integer.toString(HCVEAsyncServer.iMaxNumOfConn) + ").");
        }
        /*
         ChannelPipeline pipeline = Channels.pipeline();
         ServerHandler hdl = new ServerHandler(channelgroup, monitor);
         pipeline.addLast("handler", hdl);
         logger.log(Level.INFO, "New comming, now #channelgroup: " + Integer.toString(channelgroup.size())); 
         return pipeline;
         */
        return pipeline(new MessageDecoder(), SHARED);
    }
}

class Monitor extends Thread {

    private static final Logger logger = Logger.getLogger(ServerPipelineFactory.class.getName());
    static final int maxSN = 10 * 2;
    int sn = 0;
    static COMMANDSTATE command = COMMANDSTATE.INIT;

    public Monitor() {
    }

    public boolean isARC(){
        Channel ch;
        Iterator i = HCVEAsyncServer.channelgroup.iterator();
        while (i.hasNext()) {
            ch = (Channel) i.next();
            if (ChannelState.sn.get(ch) < sn) {
                return false;
            }
        }
        return true;
    }
    
    public void writeBack(){
        Channel ch;
        Iterator i = HCVEAsyncServer.channelgroup.iterator();
        while (i.hasNext()) {
            ch = (Channel) i.next();
            ch.write(3.14f);
        }
    }
    

    @Override
    public void run() {
        try {
            sn = (sn + 1) % maxSN;
            boolean readyMonitored = true;
            for (;;) {
                if (readyMonitored) {
                    command = COMMANDSTATE.ALLREAD;
                    Thread.sleep(1500);

                    if (HCVEAsyncServer.channelgroup.isEmpty()) {
                        continue;
                    }

                    if (!isARC()) {
                        //logger.log(Level.INFO, "Ohh... Somebody delays...");                     
                        continue;
                    }

                    command = COMMANDSTATE.ALLWRITE;
                    writeBack();                    
                    sn = (sn + 1) % maxSN;
                }
            }

        } catch (InterruptedException e) {
        }
    }
}

/**
 *
 * @author demo
 */
public class HCVEAsyncServer {
    //ChannelGroup constrcut requires name of the group as a parameter.

    static final ChannelGroup channelgroup = new DefaultChannelGroup(HCVEAsyncServer.class.getName().concat("_current"));
    static final int iPort = 7788;
    static final int iMaxNumOfConn = 2;    
    static final boolean isMonitorUp = true;
    
    /* @depreciated usage:
     *  static List<ArrayList<ChannelBuffer>> messageSet;
     *  [2013-01-18] now move this data strcuture into ChannelLocal.
     *   Monitor can access it by means of channelgroup while handlers access it through ChannelState.
     */

    public HCVEAsyncServer() {
    }

    public void run() {
        // monitor
        if (isMonitorUp)
        {
            Monitor monitor = new Monitor();
            monitor.start();
            System.out.println("Monitor starts.");
        }
        
        // boostrap
        /* NioServerSocketChannelFactory use boss threads and worker threads:
         boss threads are used for coming connection while work threads are used for non-blocking read-and-write 
         for associated channels.
         Default number of worker threads in the pool are 2* the number of available processors.
         */
        ServerBootstrap bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(), Executors.newCachedThreadPool()));

        //server factory
        ServerPipelineFactory factory = new ServerPipelineFactory();

        bootstrap.setPipelineFactory(factory);
        bootstrap.setOption("child.tcpNoDelay", true);
        bootstrap.setOption("child.keepAlive", true);
        //bootstrap.setOption("reusedAddress", true);

        bootstrap.bind(new InetSocketAddress(iPort));

        /* 
         Channel serverChannel = bootstrap.bind(new InetSocketAddress(iPort));
         channelgroup.add(serverChannel);
         System.out.println("port binds.");
         channelgroup.close().awaitUninterruptibly();
         System.out.println("server starts.");   
         */
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
