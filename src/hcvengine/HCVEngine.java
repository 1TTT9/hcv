/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcvengine;

import hcveasyncserver.HCVEAsyncServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jbox2d.callbacks.ContactImpulse;
import org.jbox2d.callbacks.ContactListener;
import org.jbox2d.collision.Manifold;
import org.jbox2d.collision.broadphase.Pair;
import org.jbox2d.collision.shapes.PolygonShape;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.BodyDef;
import org.jbox2d.dynamics.BodyType;
import org.jbox2d.dynamics.FixtureDef;
import org.jbox2d.dynamics.World;
import org.jbox2d.dynamics.contacts.Contact;

/**
 *
 * @author ggc
 */
public class HCVEngine {    
    static final Logger logger = HCVEAsyncServer.logger;
    final World world;
    static final Vec2 gravity = new Vec2(0.f, 0.f);    
    static final boolean doSleep = true;   
    static Vec2 windowSize = new Vec2(20.f, 20.f);
    static float windowWidth = 10.f;
    static float timestamp = 1.f/60.f;
    static int velocityInterations = 6;
    static int positionInteraction = 2;
    
    private Map<String, Body> vrObjectMap = null;

    public HCVEngine()
    {
        world = new World(gravity, doSleep);
        world.setContactListener(new VROContactListener());
        vrObjectMap = new HashMap<String, Body>();
    }

    public void plotWindow()
    {
        List<Vec2> wallsInfo = new ArrayList<Vec2>();
        //bottom, top, left, and right wall
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowWidth*-0.5f));
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowWidth*0.5f));
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowSize.y+windowWidth*0.5f));
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowWidth*0.5f));
        wallsInfo.add(new Vec2(windowWidth*-0.5f,             windowSize.y*0.5f));
        wallsInfo.add(new Vec2(windowWidth*0.5f, windowSize.y*0.5f));
        wallsInfo.add(new Vec2(windowSize.x+windowWidth*0.5f, windowSize.y*0.5f));
        wallsInfo.add(new Vec2(windowWidth*0.5f, windowSize.y*0.5f));
        
        Iterator it = wallsInfo.iterator();
        while (it.hasNext()) {
            BodyDef wallBodyDef = new BodyDef();
            wallBodyDef.position.set((Vec2)it.next());
            Body wallBody = world.createBody(wallBodyDef);
            PolygonShape wallBox = new PolygonShape();
            Vec2 wallBoxSize = (Vec2)it.next();
            wallBox.setAsBox(wallBoxSize.x, wallBoxSize.y);
            wallBody.createFixture(wallBox, 1.f);
        }    
    }
    
    /* Function: plotVRObject
     * Parameters: 
     *     Width, Height (Volume) - input size is the half of the indicated size.
     *     Position of X component, Y component (Position)
     *     Velocity of X component, Y component (Velocity)
     * Return: UUID of cretaed virtual object
     */
    public String plotVRObject(float width, float height, float x, float y, float vx, float vy){
        Vec2 volume = new Vec2(width, height);
        Vec2 position = new Vec2(x, y);
        Vec2 velocity = new Vec2(vx, vy);
        
        BodyDef subjectBodyDef = new BodyDef();
        subjectBodyDef.type = BodyType.DYNAMIC;  // dynamic means it is subject to forces
        subjectBodyDef.position.set(position);
        Body subjectBody = world.createBody(subjectBodyDef);
        subjectBody.setUserData(subjectBody);
        PolygonShape dynamicBox = new PolygonShape();
        dynamicBox.setAsBox(volume.x, volume.y);
        
        FixtureDef fixtureDef = new FixtureDef();
        fixtureDef.shape = dynamicBox;
        fixtureDef.restitution = 1.f;
        fixtureDef.density = 1.f;
        fixtureDef.friction = 0.3f;
        subjectBody.createFixture(fixtureDef);
        subjectBody.setLinearVelocity(velocity);
        
        String sUUID = UUID.randomUUID().toString();
        vrObjectMap.put(sUUID, subjectBody);        
        return sUUID;
    }
    
    public void step(){
        world.step(timestamp, velocityInterations, positionInteraction);
    }
    
    public float[] getPosition(String sUUID){
        Vec2 position = vrObjectMap.get(sUUID).getPosition();
        float ret[] = {position.x, position.y};
        return ret;
    }
    
}


class VROContactListener implements ContactListener {
    static final Logger logger = HCVEAsyncServer.logger;
    
    @Override
    public void beginContact(Contact contact) {
        logger.log(Level.INFO, "Collisions");        
        Object userData = contact.getFixtureA().getBody().getUserData();

        if (userData != null) {
            logger.log(Level.INFO, "A is subject.");
        }
        
        userData = null;
        userData = contact.getFixtureB().getBody().getUserData();
        
        if (userData != null) {
            logger.log(Level.INFO, "B is subject.");
        }                
    }
    
    @Override
    public void endContact(Contact contact) 
    {
    }
    
    @Override
    public void postSolve(Contact contact, ContactImpulse impulse) 
    {
//        logger.log(Level.INFO, "postSolve.");        
    }
    
    @Override
    public void preSolve(Contact contact, Manifold oldManifold) {
//        logger.log(Level.INFO, "preSolve.");        
    }
    
}
