/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcveasyncserver;

import hcvengine.HCVEngine;
import hcvengine.HCVEngineModel;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
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



class ServerPipelineFactory implements ChannelPipelineFactory {

    private static final Logger logger = HCVEAsyncServer.logger;
    private static final ServerHandler SHARED = new ServerHandler();
    
    /*
     * Get pipeline of channel handlers for both upstream and downstream
     * @return ChannelPipeline
     * @note processing order of stream message
     *   for upstream(incoming) messages, the order is MessageDecoder, HCVDataDecoder and SHARED, accordingly.
     *   for downstream(outgoing) messages, the order is SHARED, MessageEncoder, HCVDataEncoder.
     */
    @Override
    public ChannelPipeline getPipeline() throws Exception {
        if (HCVEAsyncServer.iMaxNumOfConn == 0 || HCVEAsyncServer.channelgroup.size() >= HCVEAsyncServer.iMaxNumOfConn) {
            throw new Exception("no connection allowed(maximum=" + Integer.toString(HCVEAsyncServer.iMaxNumOfConn) + ").");
        }
        logger.log(Level.INFO, "creae a new pipeline.");

        return pipeline(new MessageDecoder(), new HCVDataDecoder(),
                SHARED,
                new MessageEncoder(), new HCVDataEncoder());
    }
}


class ChannelController implements Runnable{
    final Logger logger = HCVEAsyncServer.logger;
    
    public static boolean isControllerReady = true;
    private final HCVEngineModel model;
    
    public ChannelController(HCVEngineModel argModel){
        model = argModel;
    }
    
    private void checkChannelIncomedMessageFaut(){
        Channel ch;
        Iterator i = HCVEAsyncServer.channelgroup.iterator();
        while (i.hasNext()) {
            ch = (Channel) i.next();
            if (ChannelState.inConnQueue.get(ch).isEmpty()){
                continue;
            }
            
            ConnItem item = ChannelState.inConnQueue.get(ch).pop();        
            switch(item.state){
                case INIT:
                    break;
                case LOGGNING:
                    if (ChannelState.connectionState.get(ch) == ConnectionState.LOGGNING) {
                        if (ChannelState.uuid.get(ch) == null) {
                            ChannelState.uuid.set(ch, UUID.randomUUID().toString());
                            logger.log(Level.INFO, "USER-{0}, is joining the game, uuid ={1}", new Object[]{ch, ChannelState.uuid.get(ch)});
                        } else {
                            logger.log(Level.INFO, "USER-{0}, is joining the game again, uuid ={1}", new Object[]{ch, ChannelState.uuid.get(ch)});
                        }
                    } else {
                        logger.log(Level.INFO, "USER-{0}, joined the game already, uuid ={1}", new Object[]{ch, ChannelState.uuid.get(ch)});                        
                    }
                    break;
                case MRECV:
                    if (HCVEngineModel.isFocusInWindow(item.x, item.y)){
                        logger.log(Level.INFO, "USER-{0}, is inside. Window({1}, {2}) -> World({3}, {4})", new Object[]{ch, item.x, item.y, 
                            HCVEngineModel.transformWindowToWorld(item.x, item.y).x, HCVEngineModel.transformWindowToWorld(item.x, item.y).y});  
                    }else{
                        logger.log(Level.INFO, "USER-{0}, is out of window ({1}, {2})", new Object[]{ch, item.x, item.y});                         
                    }
                    break;
                case QUIT:
                    break;
            }
        }              
    }
    
    private void updateFaut(){
        Channel ch;
        Iterator i = HCVEAsyncServer.channelgroup.iterator();
        while (i.hasNext()) {
            ch = (Channel) i.next();
            
            if (ChannelState.connectionState.get(ch) == ConnectionState.LOGGNING)
            {
                ChannelState.connectionState.set(ch, ConnectionState.READY);
            }
        }
    }
    
    private void checkChannelIncomedMessage(){
        Channel ch;
        Iterator i = HCVEAsyncServer.channelgroup.iterator();
        while (i.hasNext()) {
            ch = (Channel) i.next();
            if (ChannelState.inConnQueue.get(ch).isEmpty()){
                continue;
            }
            
            ConnItem item = ChannelState.inConnQueue.get(ch).pop();
            switch(item.state){
                case INIT:
                    break;
                case LOGGNING:
                    if (ChannelState.connectionState.get(ch) == ConnectionState.LOGGNING){
                        if (ChannelState.uuid.get(ch) == null){
                            ChannelState.uuid.set(ch, UUID.randomUUID().toString());
                            model.queueDeviceJoin(ChannelState.uuid.get(ch), HCVEngineModel.transformWindowToWorld(item.x, item.y));
                            logger.log(Level.INFO, "USER-{0}, is joining the game, uuid ={1}", new Object[]{ch, ChannelState.uuid.get(ch)});
                        }else{
                            logger.log(Level.INFO, "USER-{0}, is joining the game and waiting for registration, uuid ={1}", new Object[]{ch, ChannelState.uuid.get(ch)});
                        }
                    } else {
                        logger.log(Level.INFO, "USER-{0}, joined the game already, uuid ={1}", new Object[]{ch, ChannelState.uuid.get(ch)});                        
                    }
                    break;
                case MRECV:
                    if (HCVEngineModel.isFocusInWindow(item.x, item.y)){
//                        logger.log(Level.INFO, "USER-{0}, is inside. Window({1}, {2}) -> World({3}, {4})", new Object[]{ch, item.x, item.y, 
//                            HCVEngineModel.transformWindowToWorld(item.x, item.y).x, HCVEngineModel.transformWindowToWorld(item.x, item.y).y});                        
                        model.queueDeviceMove(ChannelState.uuid.get(ch), HCVEngineModel.transformWindowToWorld(item.x, item.y));
                    }else{
                        model.queueDeviceIdle(ChannelState.uuid.get(ch), HCVEngineModel.transformWindowToWorld(item.x, item.y));
                    }
                    break;
                case QUIT:
                    model.queueDeviceQuit(ChannelState.uuid.get(ch), HCVEngineModel.transformWindowToWorld(item.x, item.y));
                    break;
            }
        }        
    }
    
    
    public void run(){
        if (!isControllerReady){
            logger.log(Level.WARNING, "Controller is not ready. Quit");
            return;
        }
        logger.log(Level.INFO, "Controller is now running.");                

        while (true) {
            if (EngineController.isControllerReady){
                checkChannelIncomedMessage();
            }else{
                checkChannelIncomedMessageFaut();
                updateFaut();
//                float[] pos = hcvengine.HCVEngineModel.tranformWorldToWindow(hcvengine.HCVEngineModel.getVOMainPosition());
//                logger.log(Level.INFO, "VOMain Position ({0},{1}).", new Object[]{pos[0], pos[1]});  
            }
            
            try{
                Thread.sleep(20);
            }catch(InterruptedException e){
            }
        }
        
    }
}

class EngineController implements Runnable{
    final Logger logger = HCVEAsyncServer.logger;
    
    public static boolean isControllerReady = true;
    private final HCVEngineModel model;
    
    public EngineController(HCVEngineModel argModel){
        model = argModel;
        if (isControllerReady){
            model.init();
        }
    }
    
    public void run(){
        if (!isControllerReady){
            logger.log(Level.WARNING, "Controller is not ready. Quit.");
            return;
        }
        logger.log(Level.INFO, "Controller is now running.");        
        
        Channel ch;
        while (true){
            model.update();
            
            Iterator i = HCVEAsyncServer.channelgroup.iterator();
            while (i.hasNext()) {
                ch = (Channel) i.next();
                switch(ChannelState.connectionState.get(ch)){
                    case LOGGNING:
                        if (model.isDeviceExist(ChannelState.uuid.get(ch))){
                            ChannelState.connectionState.set(ch, ConnectionState.READY);
                        }
                        break;
                }
                
                switch (model.getDeviceStateByUUID(ChannelState.uuid.get(ch))){
                    case -1: //init
                    case 0:
                        ChannelState.connDeviceState.set(ch, ConnDeviceState.INIT);
                        break;
                    case 1: // sync
                        ChannelState.connDeviceState.set(ch, ConnDeviceState.SYNC);   
                        break;
                    case 2: // idle
                        ChannelState.connDeviceState.set(ch, ConnDeviceState.IDLE);
                        break;
                    case 3: // move
                        ChannelState.connDeviceState.set(ch, ConnDeviceState.MOVE);
                        break;
                }
            }
//            float[] pos = hcvengine.HCVEngineModel.tranformWorldToWindow(hcvengine.HCVEngineModel.getVOMainPosition());
//            logger.log(Level.INFO, "VOMain position World({0}, {1}) - Window({2}, {3}).", 
//                    new Object[]{hcvengine.HCVEngineModel.transformWindowToWorld(pos[0], pos[1]).x, hcvengine.HCVEngineModel.transformWindowToWorld(pos[0], pos[1]).y, pos[0], pos[1]});             
            
        }
    }
}

/**
 *
 * @author demo
 */
public class HCVEAsyncServer {
    public static final Logger logger = Logger.getLogger(ServerPipelineFactory.class.getName());
    //ChannelGroup constrcut requires name of the group as a parameter.
    static final ChannelGroup channelgroup = new DefaultChannelGroup(HCVEAsyncServer.class.getName().concat("_current"));
    
    static final int iPort = 7788;
    static final int iMaxNumOfConn = 2;
    static final DecimalFormat df = new DecimalFormat("+###.##;-###.##");

    public HCVEAsyncServer() {
    }

    public void run() {
        HCVEngineModel model = new HCVEngineModel();
        Thread engineControl = new Thread(new EngineController(model));                
        Thread channelControl = new Thread(new ChannelController(model));        
        engineControl.start();
        channelControl.start();
        
        
        logger.log(Level.INFO, "Controller begins.");

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


// to-be-abandomed
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
        //Timestamp ts = new Timestamp(System.currentTimeMillis());
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
            ts_start = new Timestamp(System.currentTimeMillis());
            //Monitor.command = COMMANDSTATE.ALLREAD;
            Monitor.command = COMMANDSTATE.INIT;
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
                        Timestamp ts_startnext = new Timestamp(System.currentTimeMillis());
                        //logger.log(Level.INFO, "MESSAGEREAD-#{0}, TIME(ms)-{1}", new Object[]{sn, ts_startnext.getTime() - ts_start.getTime()});
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


/*
 * to-be-abandomed
 */
class EngineHandler extends Thread {
    static final Logger logger = HCVEAsyncServer.logger;
    static final float sizeVOMain = 50.f;
    static final HCVEngine engine = new HCVEngine();
    static Float[] position = new Float[]{50f, 50f};
    
    public static Float[] getFauxPosition(){
        Float[] retPos;
        synchronized (position){
            retPos = position.clone();
        }
        return retPos;
    }
    
    public static void setFauxPosition(float[] xy){
        synchronized(position){
            position[0] = xy[0];
            position[1] = xy[1];
        }
    }
    
    public float[] getWindowSize(){
        return engine.getWindowSize();
    }
    
    public static float[] getPosition(String sUUID) {
        return engine.getPosition(sUUID);
    }
        
    public static float[] getVelocity(String sUUID) {
        return engine.getLinearSpeed(sUUID);
    }
    
    public static String joinGame() {
        return engine.initMJ();
        //return engine.initFauxMJ();
    }
    
    public static void quitGame(String sUUID) {
        engine.destroyMJ(sUUID);
        //engine.destroyFauxMJ(sUUID);
    }
    
    public static void update(String sUUID, float x, float y) {
        engine.updateMJMove(sUUID, x, y);
        //engine.updateFauxMJ(sUUID, x, y);
    }
    
    @Override
    public void run(){        
        engine.initWorld();
        float[] windowSize = getWindowSize();
        String sVOMain = engine.initVOMain(windowSize[0]*0.5f, windowSize[1], sizeVOMain*0.5f, sizeVOMain*0.5f, 1.f);
        setFauxPosition(new float[]{windowSize[0]*0.5f, windowSize[1]});
        logger.log(Level.INFO, "An VRObject created, id={0}", new Object[]{sVOMain});

        Float[] pos;
        while (true){
            engine.step();
            
            //position = getPosition(sVOMain);
            setFauxPosition(getPosition(sVOMain));
            //pos = getFauxPosition();
            //logger.log(Level.INFO, "VOMain: Position({0}, {1}), Velocity-y({2})", new Object[]{pos[0], pos[1], getVelocity(sVOMain)[1]});
            //Thread.sleep(10);
        }
    }    
    /*
    public void run(){
        try {
            engine.plotWindow();
            float[] windowSize = engine.getWindowSize();
            //String sVROMain = engine.plotVRObject(1.f, 1.f, 5.f, 5.f, 60.f, 60.f);
            sVOMain = engine.plotVObject(vr_size*0.5f, vr_size*0.5f, windowSize[0]*0.5f, 0.f, 0.f, 800.f);
            logger.log(Level.INFO, "An VRObject created, id={0}", new Object[]{sVOMain});
            
            
           float[] speed;
            while (true){
                engine.step();
                posVOMain = engine.getPosition(sVOMain);
                speed = engine.getLinearSpeed(sVOMain);
                //logger.log(Level.INFO, "VOMain: Position({0}, {1}), Velocity({2}, {3})", new Object[]{posVOMain[0], posVOMain[1], speed[0], speed[1]});
                Thread.sleep(10);
            }
        } catch (InterruptedException  e){
        }
    }
    */ 
}
