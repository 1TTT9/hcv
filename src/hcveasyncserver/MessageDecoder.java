/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcveasyncserver;

import java.text.DecimalFormat;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.replay.ReplayingDecoder;
import org.jboss.netty.handler.codec.replay.VoidEnum;

/**
 *
 * @author ggc
 */
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
        //return new RecvSPData(buffer.readFloat(), buffer.readFloat());
        return new ConnDeviceData(ConnDeviceState.SYNC, buffer.readFloat(), buffer.readFloat());
    }
}


@ChannelHandler.Sharable
class ServerHandler extends SimpleChannelHandler {

    private static final Logger logger = HCVEAsyncServer.logger;
    private static final DecimalFormat df = HCVEAsyncServer.df;

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        Channel ch = e.getChannel();        
        ChannelState.connectionState.set(ch, ConnectionState.LOGGNING);
        ChannelState.inConnQueue.get(ch).add(new ConnItem(ChannelState.connectionState.get(ch), null, 0f, 0f));
        ChannelState.outConnQueue.get(ch).add(new ConnItem(ConnectionState.LOGGNING, null, 0f, 0f));
        HCVEAsyncServer.channelgroup.add(ch);                
        /*
        ChannelState.command.set(e.getChannel(), COMMANDSTATE.INIT);
        logger.log(Level.INFO, "Channel {0}, now #channelgroup: {1}",
                new Object[]{e.getChannel().toString(), HCVEAsyncServer.channelgroup.size()});
        ChannelState.command.set(e.getChannel(), COMMANDSTATE.WAITREAD);
        
        ChannelState.uuidSet.set(e.getChannel(), EngineHandler.joinGame());
        */
        
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Channel ch = e.getChannel();
        
        if (e.getMessage() instanceof ConnDeviceData) {
            ConnDeviceData rdata = (ConnDeviceData) e.getMessage();
            ChannelState.inConnQueue.get(ch).add(
                    new ConnItem(ConnectionState.MRECV, ChannelState.uuid.get(ch), rdata.x, rdata.y));
            ChannelState.outConnQueue.get(ch).add(
                    new ConnItem(ConnectionState.MSEND, ChannelState.uuid.get(ch), rdata.x, rdata.y));
            
            logger.log(Level.INFO, "USER-{0}, DATA-[ X({1}), Y({2})]", new Object[]{ch, df.format(rdata.x), df.format(rdata.y)});
            ch.write(rdata);
        }
        else if (e.getMessage() instanceof RecvSPData) 
        {
            RecvSPData rdata = (RecvSPData) e.getMessage();

           //int curSN = ChannelState.readSN.get(e.getChannel());
            /* write received message  into messageSet
             *   need: get readSN, set readTS, update messageSet with sn=readSN, add readSN by 1
             */
            // update Timestamp
            //ChannelState.readTS.get(ch).add(curSN, new Timestamp(System.currentTimeMillis()));
            // write received Message
            //ChannelState.messagemapSet.get(ch).get(curSN).putAll(rdata.getSPData());
            // add readSN by 1
            //ChannelState.readSN.set(ch, (curSN + 1) % Monitor.maxSN);

            ChannelState.inConnQueue.get(ch).add(
                    new ConnItem(ConnectionState.MRECV, ChannelState.uuid.get(e.getChannel()),
                    (float)rdata.getSPData().get('x'), (float)rdata.getSPData().get('y')));
            
            logger.log(Level.INFO, "USER-{0}, DATA-[ X({1}), Y({2})]",
                    new Object[]{ch, df.format(rdata.getSPData().get('x')), df.format(rdata.getSPData().get('y'))});
            e.getChannel().write(rdata);
            
        }
        /*
        else if (e.getMessage() instanceof ChannelBuffer)  {
            ChannelBuffer buf = (ChannelBuffer) e.getMessage();
            //write-in message 
            int curSN = ChannelState.sn.get(e.getChannel());
            ChannelState.messageSet.get(e.getChannel()).get(curSN).flush();
            ChannelState.messageSet.get(e.getChannel()).get(curSN).write(buf.array());
            ChannelState.sn.set(e.getChannel(), (curSN + 1) % Monitor.maxSN);

             ByteBuffer bb = ByteBuffer.wrap(buf.array());
             logger.log(Level.INFO, "message#{0} from {1}: ({2}, {3}) --- {4}",
             new Object[]{curSN + 1, e.getChannel(), df.format(bb.getFloat()), df.format(bb.getFloat()), Thread.currentThread().getName()});
             bb.flip();

            logger.log(Level.INFO, "message#{0} from {1}: ({2}, {3}) --- {4}",
                    new Object[]{curSN + 1, e.getChannel(), df.format(buf.readFloat()), df.format(buf.readFloat()), Thread.currentThread().getName()});
            e.getChannel().write(e.getMessage());
        }*/
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        Channel ch = e.getChannel();
        //Invoked when a Channel was disconnected from its remote peer.
        logger.log(Level.INFO, "Disconnection from {0}", new Object[]{ch});
        ChannelState.connectionState.set(ch, ConnectionState.QUIT);
        ChannelState.inConnQueue.get(ch).add(
                new ConnItem( ConnectionState.QUIT, ChannelState.uuid.get(ch), 0f, 0f));
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
        Channel ch = e.getChannel();        
        //Invoked when a Channel was closed and all its related resources were released
        logger.log(Level.INFO, "Closed from {0}", new Object[]{ch});
        ChannelState.connectionState.set(ch, ConnectionState.QUIT);
        ChannelState.inConnQueue.get(ch).add(
                new ConnItem( ConnectionState.QUIT, ChannelState.uuid.get(e.getChannel()), 0f, 0f));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        //logger.log(Level.WARNING, "Unexpected expection...", e.getCause());
        Channel ch = e.getChannel();
        ChannelState.connectionState.set(ch, ConnectionState.QUIT);
        ChannelState.inConnQueue.get(ch).add(
                new ConnItem( ConnectionState.QUIT, ChannelState.uuid.get(ch), 0f, 0f));        
       ch.close();
    }
}