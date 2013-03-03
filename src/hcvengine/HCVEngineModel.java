/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcvengine;

import org.jbox2d.collision.shapes.PolygonShape;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.BodyDef;
import org.jbox2d.dynamics.BodyType;
import org.jbox2d.dynamics.Fixture;

/**
 *
 * @author ggc
 */
public class HCVEngineModel extends HCVEngineBase{
    /*
     * NOTE:
     *   In this model, as unit of window is pixel(px) but unit of box2d is meter(m)
     *   Here we assume the transformed ratio is 1 (1px/1m)
     * 
     * Maximum Acceleration Issue: 
     *   http://passover.blog.51cto.com/2431658/805943
     */
    public static final float WindowToWorldRatio = 1.0f;
    public static final Vec2 windowSize = new Vec2(800f, 600f);    
   
    

    
    Fixture m_fixture1;
    Fixture m_fixture2;    
    public static final Vec2 mainHalfSize = new Vec2(40f, 40f);
    public static final Vec2 mainInitPos = new Vec2(windowSize.x*0.5f, windowSize.y*0.5f);
    public static VOItem voMain = new VOItem(mainInitPos.x, mainInitPos.y);
    
    public static final Vec2 groundBottomV1 = new Vec2(0f, windowSize.y*0.1f);
    public static final Vec2 groundBottomV2 = new Vec2(windowSize.x, windowSize.y*0.1f);
    public static final Vec2 groundLeftV1 = new Vec2(mainInitPos.x-mainHalfSize.x*2, 0);
    public static final Vec2 groundLeftV2 = new Vec2(mainInitPos.x-mainHalfSize.x*2, windowSize.y);
    public static final Vec2 groundRightV1 = new Vec2(mainInitPos.x+mainHalfSize.x*2, 0);
    public static final Vec2 groundRightV2 = new Vec2(mainInitPos.x+mainHalfSize.x*2, windowSize.y);    
    
    
    
    /*
     * @See hcvengine.HCVEngineBase#initEngine()
     */
    @Override
    public void initEngine(){
        {
            BodyDef def = new BodyDef();
            Body ground = getWorld().createBody(def);
            
            PolygonShape shapeBottom = new PolygonShape();
            shapeBottom.setAsEdge(groundBottomV1, groundBottomV2);
            ground.createFixture(shapeBottom, 0f);
            PolygonShape shapeLeft = new PolygonShape();
            shapeLeft.setAsEdge(groundLeftV1, groundLeftV2);
            ground.createFixture(shapeLeft, 0f);
            PolygonShape shapeRight = new PolygonShape();
            shapeRight.setAsEdge(groundRightV1, groundRightV2);
            ground.createFixture(shapeRight, 0f);            
            
        }
        
        BodyDef def = new BodyDef();
        def.type = BodyType.DYNAMIC;
        
        synchronized(voMain){
            def.position.set(voMain.getPosition());
            voMain.setBody(getWorld().createBody(def));
            PolygonShape shape = new PolygonShape();
            shape.setAsBox(mainHalfSize.x, mainHalfSize.y, new Vec2(0f, 0f), 0f);
            m_fixture1 = voMain.getBody().createFixture(shape, 0.2f);
            m_fixture2 = null;
        }
    }

    public static float[] transformWorldToWindow(Vec2 p) {
        Vec2 q = p.clone();
        q = q.set(q.x * WindowToWorldRatio, q.y * WindowToWorldRatio);
        return new float[]{q.x, windowSize.y - q.y};
    }
    
    public static Vec2 transformWindowToWorld(float x, float y) {
        Vec2 p = new Vec2(x/WindowToWorldRatio, (windowSize.y-y)/WindowToWorldRatio);
        return p;
    }
    
    public static Vec2 getVOMainPosition(){
        Vec2 p;
        synchronized(voMain){
            p = voMain.getPosition();
        }
        return p.clone();
    }
    
    public static Vec2 getVOMainForce(){
        Vec2 f;
        synchronized(voMain){
            f = voMain.getForce();
        }
        return f.clone();
    }
    
    
    public static boolean isFocusInWindow(float x, float y){
        if (x>windowSize.x || x<0 || y>windowSize.y || y<0){
            return false;
        }
        return true;
    }
    
    
    public static float[] getWindowSize(){
        return new float[]{windowSize.x, windowSize.y};
    }
    
    @Override
    public void update(){
        super.update();
        synchronized(voMain){
            voMain.update();
        }
    }    
}


class VOItem{
    private Body body;
    private Vec2 position;
    private Vec2 force;
    
    public VOItem(){
        position = new Vec2(0f, 0f);
        force = new Vec2(0f, 0f);
    }
    
    public VOItem(float x, float y){
        position = new Vec2(x, y);
        force = new Vec2(0f, 0f);
    }
    
    public VOItem(float x, float y, float h, float v){
        position = new Vec2(x, y);
        force = new Vec2(h, v);
    }
    
    public void setBody(Body bd){
        body = bd;
    }
    
    public Body getBody(){
        return body;
    }
    
    public void setPosition(Vec2 p){
        position = p;
    }
    
    public Vec2 getPosition(){
        return position.clone();
    }

    public void setForce(Vec2 f){
        force = f;
    }    
    
    public Vec2 getForce(){
        return force.clone();
    }
    
    public void update(){
        position = body.getPosition();
        force = body.getLinearVelocity();
    }
}