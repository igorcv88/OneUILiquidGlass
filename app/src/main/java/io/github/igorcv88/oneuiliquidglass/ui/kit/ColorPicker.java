package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;
import io.github.igorcv88.oneuiliquidglass.config.Rgba;

/** HSV square, hue strip and alpha strip used by every user-editable colour. */
public final class ColorPicker extends LinearLayout {
    private final float[] hsv = new float[3];
    private int alpha = 255;
    private final Plane plane;
    private final Bar hue, opacity;
    private final PreviewSwatch swatch;
    private Consumer<String> listener;

    public ColorPicker(Context context, Theme theme) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(0, theme.dp(8), theme.dp(8), 0);
        plane = new Plane(context);
        plane.setContentDescription("Mapa de cor: horizontal é saturação, vertical é brilho");
        addView(plane, new LayoutParams(-1, theme.dp(220)));
        TextView hueLabel = Ui.text(context,theme,"Matiz",Theme.CAPTION,theme.inkMuted,false);
        hueLabel.setPadding(0,theme.dp(14),0,theme.dp(4));
        addView(hueLabel);
        hue = new Bar(context,true);
        hue.setContentDescription("Matiz, de vermelho a violeta");
        addView(hue,new LayoutParams(-1,theme.dp(32)));
        TextView alphaLabel=Ui.text(context,theme,"Opacidade",Theme.CAPTION,theme.inkMuted,false);
        alphaLabel.setPadding(0,theme.dp(12),0,theme.dp(4));
        addView(alphaLabel);
        opacity=new Bar(context,false);
        opacity.setContentDescription("Opacidade da cor, de transparente a sólida");
        addView(opacity,new LayoutParams(-1,theme.dp(32)));
        TextView alphaHint = Ui.text(context,theme,
                "O alpha preserva o RGB. Quanto menor a opacidade, mais o fundo altera a cor vista.",
                Theme.CAPTION,theme.inkMuted,false);
        alphaHint.setPadding(0,theme.dp(8),0,0);
        addView(alphaHint);
        TextView previewLabel = Ui.text(context,theme,"Prévia: fundo claro  /  fundo escuro",
                Theme.CAPTION,theme.inkMuted,false);
        previewLabel.setPadding(0,theme.dp(10),0,0);
        addView(previewLabel);
        swatch=new PreviewSwatch(context);
        swatch.setContentDescription("Cor selecionada sobre branco à esquerda e escuro à direita");
        LayoutParams p=new LayoutParams(-1,theme.dp(32));
        p.topMargin=theme.dp(12);
        addView(swatch,p);
        setColor("ffffff");
    }

    public void listener(Consumer<String> l) { listener=l; }

    public void setColor(String hex) {
        int color=Rgba.argb(hex);
        Color.colorToHSV(color,hsv);
        alpha=Color.alpha(color);
        refresh();
    }

    private int rgb() { return Color.HSVToColor(hsv); }
    private int selected() { return Color.HSVToColor(alpha,hsv); }
    private void changed() {
        refresh();
        if(listener!=null)listener.accept(Rgba.hex(selected()));
    }

    private void refresh() {
        plane.invalidate();
        hue.invalidate();
        opacity.invalidate();
        swatch.invalidate();
    }

    /** The RGB stays identical; only its visible composite changes with the backdrop. */
    private final class PreviewSwatch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PreviewSwatch(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float w=getWidth(),h=getHeight();
            if (w<=0||h<=0)return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfffafafa);
            canvas.drawRect(0,0,w/2,h,paint);
            paint.setColor(0xff15232d);
            canvas.drawRect(w/2,0,w,h,paint);
            paint.setColor(selected());
            canvas.drawRect(0,0,w,h,paint);
        }
    }

    private final class Plane extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Plane(Context c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE,null); }
        @Override protected void onDraw(Canvas c) {
            float w=getWidth(),h=getHeight();
            if(w<=0||h<=0)return;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0,0,w,0,Color.WHITE,Color.HSVToColor(new float[]{hsv[0],1,1}),Shader.TileMode.CLAMP));
            c.drawRoundRect(0,0,w,h,10,10,paint);
            paint.setShader(new LinearGradient(0,0,0,h,Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP));
            c.drawRoundRect(0,0,w,h,10,10,paint);
            paint.setShader(null);
            float x=hsv[1]*w,y=(1-hsv[2])*h;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5);paint.setColor(Color.WHITE);
            c.drawCircle(x,y,10,paint);paint.setStrokeWidth(2);paint.setColor(Color.BLACK);
            c.drawCircle(x,y,13,paint);paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE){
                hsv[1]=Math.max(0,Math.min(1,e.getX()/Math.max(1,getWidth())));
                hsv[2]=1-Math.max(0,Math.min(1,e.getY()/Math.max(1,getHeight())));
                changed();return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP){performClick();return true;}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }

    private final class Bar extends View {
        private final boolean isHue;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Bar(Context c,boolean hue){super(c);isHue=hue;}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
            if(isHue){
                paint.setShader(new LinearGradient(0,0,w,0,new int[]{0xffff0000,0xffffff00,0xff00ff00,0xff00ffff,0xff0000ff,0xffff00ff,0xffff0000},null,Shader.TileMode.CLAMP));
            }else{
                // The checker pattern makes partially transparent colours visible on either theme.
                paint.setShader(null);
                float cell=h/4;
                for(int iy=0;iy<4;iy++)for(int ix=0;ix<(int)(w/cell)+1;ix++){
                    paint.setColor(((ix+iy)&1)==0?0xffd7d7d7:0xffffffff);
                    c.drawRect(ix*cell,iy*cell,Math.min(w,(ix+1)*cell),(iy+1)*cell,paint);
                }
                paint.setShader(new LinearGradient(0,0,w,0,rgb()&0xffffff,rgb(),Shader.TileMode.CLAMP));
            }
            paint.setColor(Color.WHITE);
            c.drawRoundRect(0,0,w,h,h/2,h/2,paint);
            paint.setShader(null);
            float pos=(isHue?hsv[0]/360f:alpha/255f)*w;
            paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);
            c.drawCircle(pos,h/2,h/2-4,paint);
            paint.setColor(Color.BLACK);paint.setStrokeWidth(2);c.drawCircle(pos,h/2,h/2-1,paint);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE){
                float progress=Math.max(0,Math.min(1,e.getX()/Math.max(1,getWidth())));
                if(isHue)hsv[0]=progress*359.99f;else alpha=Math.round(255*progress);
                changed();return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP){performClick();return true;}
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
