/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcveasyncserver;

import hcvengine.HCVEngineModel;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

/**
 *
 * @author ggc
 */

class MessageEncoder extends SimpleChannelHandler {
    
    private static final Logger logger = HCVEAsyncServer.logger;
    private static DecimalFormat df = HCVEAsyncServer.df;

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        
        Channel ch = e.getChannel();
        if (e.getMessage() instanceof ConnDeviceData){
            ConnDeviceData sdata = (ConnDeviceData) e.getMessage();
            ChannelBuffer cb = ChannelBuffers.buffer(sdata.dataSize);
            
            switch(sdata.state){
                case SYNC:
                    cb.writeByte('5');
                    cb.writeFloat(sdata.x);
                    cb.writeFloat(sdata.y);
                    break;
                case IDLE:
                    cb.writeByte('6');
                    cb.writeFloat(sdata.x);
                    cb.writeFloat(sdata.y);
                    break;
                case MOVE:
                    cb.writeByte('7');
                    cb.writeFloat(sdata.x);
                    cb.writeFloat(sdata.y);
                    break;                    
            }
            Channels.write(ctx, e.getFuture(), cb);            
         
        }
        
        else if (e.getMessage() instanceof RecvSPData) {
            SendSPData sdata = new SendSPData();
            //int lastSN = ChannelState.writeSN.get(e.getChannel()); //***  current wiat-to-write SN  ***
            //int curSN = (lastSN + 1) % Monitor.maxSN;
            
            float[] pos;
            if (ChannelState.outConnQueue.get(e.getChannel()).isEmpty()) {
                pos = new float[]{-1f, -1f};
            }else{
                pos = new float[]{ChannelState.outConnQueue.get(e.getChannel()).pop().x, ChannelState.outConnQueue.get(e.getChannel()).pop().y};
            }
            
            logger.log(Level.INFO, "sendto {0}: ###### ({1}, {2})  #####", new Object[]{e.getChannel(), pos[0], pos[1]});
            sdata.setSyncVOMainData(pos[0], pos[1]);
            logger.log(Level.INFO, "sendto {0}: SYNC", new Object[]{e.getChannel()});
            
            Channels.write(ctx, e.getFuture(), sdata);
            
        } else {
            logger.log(Level.INFO, "I am here... not recvspdata");
            super.writeRequested(ctx, e);
        }
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
            case MSYNC:
                cbSize = 9; // 1 +4 +4
                break;
            case READY:
                cbSize = 17; // 1 +4 +4 +4 +4
                break;
            case ADJUST:
                cbSize = 5;  // 1 +4
                break;
            default:
                break;
        }
        return ChannelBuffers.buffer(cbSize);
        //return buffer(cbSize);
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Channel ch = e.getChannel();
        
        if (e.getMessage() instanceof ConnDeviceData) {
            ConnDeviceData sdata = (ConnDeviceData) e.getMessage();

            if (ChannelState.outConnQueue.get(ch).isEmpty()) {
                logger.log(Level.WARNING, "There must be something wrong. It has to have an outgoing messsage for every incame request");
                return;
            }

            ConnItem item = ChannelState.outConnQueue.get(ch).pop();
            float[] pos = hcvengine.HCVEngineModel.transformWorldToWindow(hcvengine.HCVEngineModel.getVOMainPosition());
            switch(item.state){
                case LOGGNING:
                    if (ChannelState.connectionState.get(ch) == ConnectionState.READY){
                        logger.log(Level.INFO, "USER-{0}, joined the game successfully", new Object[]{ch}); 
                        sdata.reset(ConnDeviceState.SYNC, pos[0], pos[1]);
                    }else{
                        logger.log(Level.INFO, "USER-{0}, still waiting for joining.", new Object[]{ch});                        
                        sdata.reset(ConnDeviceState.SYNC, sdata.x, sdata.y);                        
                    }
                    break;
                case MSEND:
                   switch (ChannelState.connDeviceState.get(ch)){
                        case INIT:
                        case SYNC:
                            sdata.reset(ConnDeviceState.SYNC,  pos[0], pos[1]);
                            break;
                        case IDLE:
                            sdata.reset(ConnDeviceState.IDLE, pos[0], pos[1]);
                            break;
                        case MOVE:
                            sdata.reset(ConnDeviceState.MOVE, pos[0], pos[1]);
                            break;
                    }
                    logger.log(Level.INFO, "USER-{0} MSEND, DATA-[ X({1}), Y({2})]", new Object[]{ch, df.format(sdata.x), df.format(sdata.y)});                    
                    break;
                case INIT:                    
                case MRECV:
                case QUIT:
                    break;
            }
            
            Channels.write(ctx, e.getFuture(), sdata);
            
        }else if (e.getMessage() instanceof SendSPData) {
            //logger.log(Level.INFO, "transform SendSPData into ChannelBuffer");

            SendSPData sdata = (SendSPData) e.getMessage();
            ChannelBuffer cb = this.getChannelBuffer(sdata.getContentType());
            Map wdata = sdata.getData();

            if (sdata.getContentType() == WRITETYPE.SYNC) {
                cb.writeByte('1');
            } else if (sdata.getContentType() == WRITETYPE.MSYNC) {
                cb.writeByte('5');
                cb.writeFloat((float) wdata.get('x'));
                cb.writeFloat((float) wdata.get('y'));                
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
            Channels.write(ctx, e.getFuture(), cb);
            //write(ctx, e.getFuture(), cb);
        } else if (e.getMessage() instanceof Float) {
            //ChannelBuffer cb = buffer(4);
            ChannelBuffer cb = ChannelBuffers.buffer(4);
            cb.writeFloat((float) e.getMessage());
            logger.log(Level.INFO, "write float({0}) --- {1}",
                    new Object[]{e.getMessage(), Thread.currentThread().getName()});
            
            Channels.write(ctx, e.getFuture(), cb);            
            //write(ctx, e.getFuture(), cb);
        } else {
            logger.log(Level.INFO, "I am here... not any type");
            super.writeRequested(ctx, e);
        }
    }
}
