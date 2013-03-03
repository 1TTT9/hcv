/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcveasyncserver;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelLocal;

/**
 *
 * @author ggc
 */

enum ConnectionState {
    INIT, LOGGNING, READY, QUIT, MRECV, MSEND
}

enum ConnDeviceState {
    INIT, SYNC, IDLE, MOVE
}


enum WRITETYPE {
    INIT, SYNC, MSYNC, WAIT, READY, ADJUST,
}

enum COMMANDSTATE {
    OFF, INIT, SYNCREAD, SYNCWRITE, WAITREAD, WAITWRITE, ALLREAD, ALLWRITE,
}

final class ChannelState {

    static final ChannelLocal<String> uuid = new ChannelLocal<String>() {
        @Override
        protected String initialValue(Channel ch) {
            return null;
        }
    };
    
    static final ChannelLocal<ConnectionState> connectionState = new ChannelLocal<ConnectionState>() {
        @Override
        protected ConnectionState initialValue(Channel ch) {
            return ConnectionState.INIT;
        }
    };
    
    static final ChannelLocal<ConnDeviceState> connDeviceState = new ChannelLocal<ConnDeviceState>() {
        @Override
        protected ConnDeviceState initialValue(Channel ch) {
            return ConnDeviceState.INIT;
        }
    };    
    
    static final ChannelLocal<LinkedList<ConnItem>> inConnQueue = new ChannelLocal<LinkedList<ConnItem>>() {
        @Override
        protected LinkedList<ConnItem> initialValue(Channel ch) {
            return new LinkedList<ConnItem>();
        }
    };
    
    static final ChannelLocal<LinkedList<ConnItem>> outConnQueue = new ChannelLocal<LinkedList<ConnItem>>() {
        @Override
        protected LinkedList<ConnItem> initialValue(Channel ch) {
            return new LinkedList<ConnItem>();
        }
    };    
    
    static final ChannelLocal<COMMANDSTATE> command = new ChannelLocal<COMMANDSTATE>() {
        @Override
        protected COMMANDSTATE initialValue(Channel ch) {
            return COMMANDSTATE.OFF;
        }
    };
    
    /** The expected received message's SN
     * @return
     *  readSN: next sequential number (SN) which indicates next received message number. Start from 0.
     * */
    static final ChannelLocal<Integer> readSN = new ChannelLocal<Integer>() {
        @Override
        protected Integer initialValue(Channel ch) {
            return 0;
        }
    };
    
    /** Timestamp used to record when we received the specified message
     *  @return
     *   MapList of Timestamp
     * */
    static final ChannelLocal<List<Timestamp>> readTS = new ChannelLocal<List<Timestamp>>() {
        @Override
        protected List<Timestamp> initialValue(Channel ch) {
            List<Timestamp> ms = new ArrayList<>();
            //Timestamp ts = new Timestamp(System.currentTimeMillis());
            Timestamp ts = new Timestamp(0L);
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(ts);
            };
            return (List<Timestamp>) ms;
        }
    };

    /** Sequential number of the last written message
     *  @ return: Last written SN. Remmber its intial value is -1 coz our messages begin from 0.
     * */    
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
            Timestamp ts = new Timestamp(System.currentTimeMillis());
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(ts);
            }
            return (List<Timestamp>) ms;
        }
    };
    
    /** depreciated. Need to be removed in the future. */
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
            }
            return (ArrayList<Map>) ms;
        }
    };
    
    /** depreciated. Need to be removed in the future. */    
    // old(2013-01-24), use map instead.
    static final ChannelLocal<ArrayList<ByteArrayOutputStream>> messageSet = new ChannelLocal<ArrayList<ByteArrayOutputStream>>() {
        @Override
        protected ArrayList<ByteArrayOutputStream> initialValue(Channel ch) {
            List<ByteArrayOutputStream> ms = new ArrayList<>();
            for (int i = 0; i < Monitor.maxSN; i++) {
                ms.add(new ByteArrayOutputStream());
            }
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

    public void setSyncVOMainData(float x, float y) {
        this.wt = WRITETYPE.MSYNC;
        mdata.put('x', x);
        mdata.put('y', y);        
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

class ConnDeviceData {
    public ConnDeviceState state;
    public int dataSize;
    public float x;
    public float y;
    public float h;
    public float v;

   public ConnDeviceData(ConnDeviceState s){
        state = s;
        dataSize = 1;
    }
    
   public ConnDeviceData(ConnDeviceState s, float xx, float yy){
        state = s;
        x = xx;
        y = yy;
        dataSize = 1 + 4*2;                
    }
     
    public ConnDeviceData(ConnDeviceState s, float xx, float yy, float hh, float vv){
        state = s;
        x = xx;
        y = yy;
        h = hh;
        v = vv;
        dataSize = 1 + 4*4;
    }
    
    public void reset(ConnDeviceState s, float xx, float yy){
        state = s;
        x = xx;
        y = yy;
        dataSize = 1 + 4*2;
    }
}


class ConnItem {
    
    public ConnectionState state;
    public float x;
    public float y;
    public String uuid;
    
    public ConnItem(ConnectionState cs, String s, float xx, float yy) {
        state = cs;
        x = xx;
        y = yy;
        uuid = s;
    }
}