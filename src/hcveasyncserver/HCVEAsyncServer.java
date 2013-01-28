/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcveasyncserver;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import static org.jboss.netty.buffer.ChannelBuffers.*;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelLocal;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import static org.jboss.netty.channel.Channels.*;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.replay.*;
import org.jboss.netty.handler.timeout.WriteTimeoutHandler;

enum WRITETYPE {

    INIT, SYNC, WAIT, READY, ADJUST,
}

enum COMMANDSTATE {

    OFF, INIT, SYNCREAD, SYNCWRITE, WAITREAD, WAITWRITE, ALLREAD, ALLWRITE,
}

final class ChannelState {

    static final ChannelLocal<Boolean> loginIn = new ChannelLocal<Boolean>() {
        @Override
        protected Boolean initialValue(Channel ch) {
            return false;
        }
    };
    static final ChannelLocal<COMMANDSTATE> command = new ChannelLocal<COMMANDSTATE>() {
        @Override
        protected COMMANDSTATE initialValue(Channel ch) {
            return COMMANDSTATE.OFF;
        }
    };
    static final ChannelLocal<Integer> readSN = new ChannelLocal<Integer>() {
        @Override
        protected Integer initialValue(Channel ch) {
            return 0;
        }
    };
    static final ChannelLocal<List<Timestamp>> readTS = new ChannelLocal<List<Timestamp>>() {
        @Override
        protected List<Timestamp> initialValue(Channel ch) {
            List<Timestamp> ms = new ArrayList<>();
            //Timestamp ts = new Timestamp(Calendar.getInstance().getTime().getTime());
            Timestamp ts = new Timestamp(0L);
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(ts);
            };
            return (List<Timestamp>) ms;
        }
    };
    static final ChannelLocal<Integer> writeSN = new ChannelLocal<Integer>() {
        @Override
        protected Integer initialValue(Channel ch) {
            return -1;
        }
    };
    static final ChannelLocal<List<Timestamp>> writeTS = new ChannelLocal<List<Timestamp>>() {
        @Override
        protected List<Timestamp> initialValue(Channel ch) {
            List<Timestamp> ms = new ArrayList<>();
            Timestamp ts = new Timestamp(Calendar.getInstance().getTime().getTime());
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(ts);
            };
            return (List<Timestamp>) ms;
        }
    };
    static final ChannelLocal<Integer> sn = new ChannelLocal<Integer>() {
        @Override
        protected Integer initialValue(Channel ch) {
            return 0;
        }
    };
    static final ChannelLocal<ArrayList<Map>> messagemapSet = new ChannelLocal<ArrayList<Map>>() {
        @Override
        protected ArrayList<Map> initialValue(Channel ch) {
            List<Map> ms = new ArrayList<>();
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(new HashMap());
            };
            return (ArrayList<Map>) ms;
        }
    };
    // old(2013-01-24), use map instead.
    static final ChannelLocal<ArrayList<ByteArrayOutputStream>> messageSet = new ChannelLocal<ArrayList<ByteArrayOutputStream>>() {
        @Override
        protected ArrayList<ByteArrayOutputStream> initialValue(Channel ch) {
            List<ByteArrayOutputStream> ms = new ArrayList<>();
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(new ByteArrayOutputStream());
            };
            return (ArrayList<ByteArrayOutputStream>) ms;
        }
    };
}

class SendSPData {

    private WRITETYPE wt;
    /* Tell clients
     * (1) sync  : server is synchronizing all channels.
     * (2) ready : server is ready to compute.
     * (3) wait  : server is waiting for a incomplete SN 
     * (4) pos   : server now sends a just-finished new position and feedback
     * (5) adj   : server suggests you to adjust your fps
     */
    private Map mdata;

    public SendSPData() {
        this.wt = WRITETYPE.INIT;
        mdata = new HashMap();
    }

    public void setSyncData() {
        this.wt = WRITETYPE.SYNC;
    }

    public void setWaitData() {
        this.wt = WRITETYPE.WAIT;
    }

    public void setReadyData(float x, float y, float v, float h) {
        this.wt = WRITETYPE.READY;
        mdata.put('x', x);
        mdata.put('y', y);
        mdata.put('h', h);
        mdata.put('v', v);
    }

    public void setAdjustData(int fps) {
        this.wt = WRITETYPE.ADJUST;
        mdata.put("fps", fps);
    }

    public Map getData() {
        return this.mdata;
    }

    public WRITETYPE getContentType() {
        return this.wt;
    }
}

class RecvSPData {

    private final Map mdata;

    public RecvSPData(float x, float y) {
        this.mdata = new HashMap();
        this.mdata.put('x', x);
        this.mdata.put('y', y);
    }

    public Map getSPData() {
        return mdata;
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

class HCVDataDecoder extends ReplayingDecoder<VoidEnum> {

    private static final Logger logger = HCVEAsyncServer.logger;

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel handler, ChannelBuffer buffer, VoidEnum state) {
        return new RecvSPData(buffer.readFloat(), buffer.readFloat());
    }
}

class HCVDataEncoder extends SimpleChannelHandler {

    private static final Logger logger = HCVEAsyncServer.logger;
    private static DecimalFormat df = HCVEAsyncServer.df;

    public static ChannelBuffer getChannelBuffer(WRITETYPE wt) {
        int cbSize = 1;
        switch (wt) {
            case INIT:
            case SYNC:
            case WAIT:
                break;
            case READY:
                cbSize = 17;
                break;
            case ADJUST:
                cbSize = 5;
                break;
            default:
                break;
        }
        return buffer(cbSize);
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {

        if (e.getMessage() instanceof SendSPData) {
            //logger.log(Level.INFO, "transform SendSPData into ChannelBuffer");

            SendSPData sdata = (SendSPData) e.getMessage();
            ChannelBuffer cb = this.getChannelBuffer(sdata.getContentType());
            Map wdata = sdata.getData();

            if (sdata.getContentType() == WRITETYPE.SYNC) {
                cb.writeByte('1');
            } else if (sdata.getContentType() == WRITETYPE.WAIT) {
                cb.writeByte('2');
            } else if (sdata.getContentType() == WRITETYPE.ADJUST) {
                cb.writeByte('4');
                cb.writeFloat(3.14f);
            } else if (sdata.getContentType() == WRITETYPE.READY) {
                cb.writeByte('3');
                cb.writeFloat((float) wdata.get('x'));
                cb.writeFloat((float) wdata.get('y'));
                cb.writeFloat((float) wdata.get('h'));
                cb.writeFloat((float) wdata.get('v'));
            } else {
                cb.writeByte(128);
                logger.log(Level.SEVERE, "No content type found. This line should not happen");
            }
            write(ctx, e.getFuture(), cb);
        } else if (e.getMessage() instanceof Float) {
            ChannelBuffer cb = buffer(4);
            cb.writeFloat((float) e.getMessage());
            logger.log(Level.INFO, "write float({0}) --- {1}",
                    new Object[]{e.getMessage(), Thread.currentThread().getName()});
            write(ctx, e.getFuture(), cb);
        } else {
            logger.log(Level.INFO, "I am here... not any type");
            super.writeRequested(ctx, e);
        }
    }
}

class MessageEncoder extends SimpleChannelHandler {

    private static final Logger logger = HCVEAsyncServer.logger;
    private static DecimalFormat df = HCVEAsyncServer.df;

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() instanceof RecvSPData) {
            SendSPData sdata = new SendSPData();
            int lastSN = ChannelState.writeSN.get(e.getChannel()); //***  current wiat-to-write SN  ***
            int curSN = (lastSN + 1) % Monitor.maxSN;
            
            if (Monitor.command == COMMANDSTATE.INIT) {
                sdata.setSyncData();
                logger.log(Level.INFO, "sendto {0}: SYNC", new Object[]{e.getChannel()});
            } else {
                String msg = "";
                if (Monitor.writeSN < 0)
                {
                    msg = msg.concat("WAIT_BEFOREZEROSTART");
                    sdata.setReadyData(-1f, -1f, -1f, -1f);
                    //ChannelState.writeTS.get(e.getChannel()).add(lastSN, new Timestamp(Calendar.getInstance().getTime().getTime()));
                    curSN = lastSN;
                    /*
                    logger.log(Level.INFO, "USER-{0}, WAIT_BEFOREZEROSTART-#{1}, DATA-[X({2}), Y({3}), H({4}), V({5})]",
                            new Object[]{e.getChannel(), lastSN,
                                df.format(sdata.getData().get('x')), df.format(sdata.getData().get('y')),
                                df.format(sdata.getData().get('h')), df.format(sdata.getData().get('v'))}); */
                } else {
                    if (lastSN < 0) {
                        msg = msg.concat("WAIT_ZEROSTART");
                        sdata.setReadyData(0f, 0f, 0f, 0f);
                        ChannelState.writeSN.set(e.getChannel(), curSN);
                        ChannelState.writeTS.get(e.getChannel()).add(curSN, new Timestamp(Calendar.getInstance().getTime().getTime()));
                        /*
                        logger.log(Level.INFO, "USER-{0}, WAIT_ZEROSTART-#{1}, DATA-[X({2}), Y({3}), H({4}), V({5})]",
                                new Object[]{e.getChannel(), curSN,
                                    df.format(sdata.getData().get('x')), df.format(sdata.getData().get('y')),
                                    df.format(sdata.getData().get('h')), df.format(sdata.getData().get('v'))});
                                    */
                    } else if (Monitor.writeTS.get(lastSN).getTime() < ChannelState.writeTS.get(e.getChannel()).get(lastSN).getTime()) {
                        msg = msg.concat("READY");
                        sdata.setReadyData(3.14f, 3.14f, 3.14f, 3.14f);
                        ChannelState.writeSN.set(e.getChannel(), curSN);
                        ChannelState.writeTS.get(e.getChannel()).add(curSN, new Timestamp(Calendar.getInstance().getTime().getTime()));
                        /*
                        logger.log(Level.INFO, "USER-{0}, READY-#{1}, DATA-[X({2}), Y({3}), H({4}), V({5})]",
                                new Object[]{e.getChannel(), curSN,
                                    df.format(sdata.getData().get('x')), df.format(sdata.getData().get('y')),
                                    df.format(sdata.getData().get('h')), df.format(sdata.getData().get('v'))});
                                    */ 
                    } else {
                        msg = msg.concat("WAIT_ZEROSTART_OTHER");
                        /*
                         sdata.setWaitData();
                         logger.log(Level.INFO, "sendto {0}: WAIT --- {1}",
                         new Object[]{e.getChannel(), Thread.currentThread().getName()});
                         */
                        sdata.setReadyData(0f, 0f, 0f, 0f);
                        ChannelState.writeTS.get(e.getChannel()).add(lastSN, new Timestamp(Calendar.getInstance().getTime().getTime()));
                        curSN = lastSN;
                        /*
                        logger.log(Level.INFO, "USER-{0}, WAIT_ZEROSTART-#{1}, DATA-[X({2}), Y({3}), H({4}), V({5})]",
                                new Object[]{e.getChannel(), lastSN,
                                    df.format(sdata.getData().get('x')), df.format(sdata.getData().get('y')),
                                    df.format(sdata.getData().get('h')), df.format(sdata.getData().get('v'))});
                                    */ 
                    }
                }
                logger.log(Level.INFO, "USER-{0}, {6}-#{1}, DATA-[X({2}), Y({3}), H({4}), V({5})]",
                        new Object[]{e.getChannel(), curSN,
                            df.format(sdata.getData().get('x')), df.format(sdata.getData().get('y')),
                            df.format(sdata.getData().get('h')), df.format(sdata.getData().get('v')), msg});
                
            }
            write(ctx, e.getFuture(), sdata);
        } else {
            logger.log(Level.INFO, "I am here... not recvspdata");
            super.writeRequested(ctx, e);
        }
    }
}

@Sharable
class ServerHandler extends SimpleChannelHandler {

    private static final Logger logger = HCVEAsyncServer.logger;
    private static DecimalFormat df = HCVEAsyncServer.df;

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        ChannelState.loginIn.set(e.getChannel(), true);
        HCVEAsyncServer.channelgroup.add(e.getChannel());
        ChannelState.command.set(e.getChannel(), COMMANDSTATE.INIT);
        logger.log(Level.INFO, "Channel {0}, now #channelgroup: {1}",
                new Object[]{e.getChannel().toString(), HCVEAsyncServer.channelgroup.size()});
        ChannelState.command.set(e.getChannel(), COMMANDSTATE.WAITREAD);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() instanceof RecvSPData) 
        {
            RecvSPData rdata = (RecvSPData) e.getMessage();

            int curSN = ChannelState.readSN.get(e.getChannel());
            /* write received message  into messageSet
             *   need: get readSN, set readTS, update messageSet with sn=readSN, add readSN by 1
             */
            // update Timestamp
            ChannelState.readTS.get(e.getChannel()).add(curSN, new Timestamp(Calendar.getInstance().getTime().getTime()));
            // write received Message
            ChannelState.messagemapSet.get(e.getChannel()).get(curSN).putAll(rdata.getSPData());
            // add readSN by 1
            ChannelState.readSN.set(e.getChannel(), (curSN + 1) % Monitor.maxSN);

            logger.log(Level.INFO, "USER-{0}, RECV-#{1}, DATA-[ X({2}), Y({3})]",
                    new Object[]{e.getChannel(), curSN, df.format(rdata.getSPData().get('x')), df.format(rdata.getSPData().get('y'))});
            
            e.getChannel().write(rdata);
        } else if (e.getMessage() instanceof ChannelBuffer)  {
            ChannelBuffer buf = (ChannelBuffer) e.getMessage();
            //write-in message 
            int curSN = ChannelState.sn.get(e.getChannel());
            ChannelState.messageSet.get(e.getChannel()).get(curSN).flush();
            ChannelState.messageSet.get(e.getChannel()).get(curSN).write(buf.array());
            ChannelState.sn.set(e.getChannel(), (curSN + 1) % Monitor.maxSN);

            /*
             ByteBuffer bb = ByteBuffer.wrap(buf.array());
             logger.log(Level.INFO, "message#{0} from {1}: ({2}, {3}) --- {4}",
             new Object[]{curSN + 1, e.getChannel(), df.format(bb.getFloat()), df.format(bb.getFloat()), Thread.currentThread().getName()});
             bb.flip();
             */
            logger.log(Level.INFO, "message#{0} from {1}: ({2}, {3}) --- {4}",
                    new Object[]{curSN + 1, e.getChannel(), df.format(buf.readFloat()), df.format(buf.readFloat()), Thread.currentThread().getName()});
            e.getChannel().write(e.getMessage());
        }
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        //Invoked when a Channel was disconnected from its remote peer.
        logger.log(Level.INFO, "Disconnection from {0}", new Object[]{e.getChannel()});
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
        //Invoked when a Channel was closed and all its related resources were released
        logger.log(Level.INFO, "Closed from {0}", new Object[]{e.getChannel()});
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        //logger.log(Level.WARNING, "Unexpected expection...", e.getCause());
        e.getChannel().close();
    }
}

class ServerPipelineFactory implements ChannelPipelineFactory {

    private static final Logger logger = HCVEAsyncServer.logger;
    private static final ServerHandler SHARED = new ServerHandler();

    public ServerPipelineFactory() {
        super();
    }

    @Override
    public ChannelPipeline getPipeline() throws Exception {
        if (HCVEAsyncServer.iMaxNumOfConn == 0 || HCVEAsyncServer.channelgroup.size() >= HCVEAsyncServer.iMaxNumOfConn) {
            throw new Exception("no connection allowed(maximum=" + Integer.toString(HCVEAsyncServer.iMaxNumOfConn) + ").");
        }
        logger.log(Level.INFO, "creae a new pipeline.");

        return pipeline(new MessageDecoder(), new HCVDataDecoder(),
                SHARED,
                new HCVDataEncoder(), new MessageEncoder());
    }
}

class Monitor extends Thread {

    static final Logger logger = HCVEAsyncServer.logger;
    static final int maxSN = 100 * 2;
    static int sn = 0;
    static Timestamp ts_start = null;
    static int writeSN = -1;
    static List<Timestamp> writeTS = null;
    static COMMANDSTATE command = COMMANDSTATE.INIT;

    public static void initWriteTS() 
    {
        writeTS = new ArrayList();
        //Timestamp ts = new Timestamp(Calendar.getInstance().getTime().getTime());
        Timestamp ts = new Timestamp(0L);
        for (int i = 0; i < Monitor.maxSN; i++) { writeTS.add(ts); };
    }

    public boolean isAllChannelsReadCompleted() {
        Channel ch;
        Iterator i = HCVEAsyncServer.channelgroup.iterator();
        while (i.hasNext()) {
            ch = (Channel) i.next();
            if (ChannelState.readTS.get(ch).get(sn).getTime() == 0L) { return false; }
            //else if (ChannelState.readTS.get(ch).get(sn).getTime() < ts_start.getTime()) { return false; }
        }
        return true;
    }

    public boolean isARC() {
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

    public void updateSN(Timestamp new_ts) {
        writeSN = sn;
        writeTS.add(writeSN, new_ts);
        sn = (sn + 1) % maxSN;
    }
    
    
    @Override
    public void run() {
        try {
            boolean readyMonitored = true;
            ts_start = new Timestamp(Calendar.getInstance().getTime().getTime());
            Monitor.command = COMMANDSTATE.ALLREAD;
            for (;;) {
                if (readyMonitored)
                {
                    //command = COMMANDSTATE.ALLREAD;
                    if (HCVEAsyncServer.channelgroup.isEmpty()) { 
                        Thread.sleep(150);
                        continue; 
                    }
                    Thread.sleep(150);
                    if (isAllChannelsReadCompleted()) {
                        Timestamp ts_startnext = new Timestamp(Calendar.getInstance().getTime().getTime());
                        logger.log(Level.INFO, "MESSAGEREAD-#{0}, TIME(ms)-{1}", new Object[]{sn, ts_startnext.getTime() - ts_start.getTime()});
                        ts_start = ts_startnext;
                        updateSN(ts_startnext);
                    }
                    /*
                    if (!isARC()) { continue; }
                    command = COMMANDSTATE.ALLWRITE;
                    sn = (sn + 1) % maxSN;
                    */ 
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

    static final Logger logger = Logger.getLogger(ServerPipelineFactory.class.getName());
    //ChannelGroup constrcut requires name of the group as a parameter.    
    static final ChannelGroup channelgroup = new DefaultChannelGroup(HCVEAsyncServer.class.getName().concat("_current"));
    static final int iPort = 7788;
    static final int iMaxNumOfConn = 2;
    static final boolean isMonitorUp = true;
    static final DecimalFormat df = new DecimalFormat("+###.##;-###.##");

    public HCVEAsyncServer() {
    }

    public void run() {
        // monitor
        Monitor.initWriteTS();
        if (isMonitorUp) {
            Monitor monitor = new Monitor();
            monitor.start();
            logger.log(Level.INFO, "Monitor starts.");
        }

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
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        new HCVEAsyncServer().run();
    }
}
