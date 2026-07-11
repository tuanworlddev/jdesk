package dev.jdesk.instance;
public final class SingleInstanceException extends Exception {
    private static final long serialVersionUID=1L;
    public SingleInstanceException(String m){super(m);} public SingleInstanceException(String m,Throwable c){super(m,c);}
}
