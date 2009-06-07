package se.sics.mspsim.net;

import java.io.InputStream;
import java.io.OutputStream;

import se.sics.mspsim.util.Utils;

public class TCPConnection {
  // States of the connection
  public static final int CLOSED = 0;
  public static final int LISTEN = 1;
  public static final int SYN_SENT = 2;
  public static final int SYN_RECEIVED = 3;
  public static final int ESTABLISHED = 4;
  public static final int CLOSE_WAIT = 5;
  public static final int LAST_ACK = 6;
  public static final int FIN_WAIT_1 = 7;
  public static final int FIN_WAIT_2 = 8;
  public static final int CLOSING = 9;
  public static final int TIME_WAIT = 10;
  
  public static final long TIME_WAIT_MILLIS = 1000;

  // my port & IP (IP can be null here...)
  int localPort;
  byte[] localIP;
  // other port
  int externalPort = -1;
  byte[] externalIP;

  int state;
  
  /* sent unacked and nextSend byte */
  int sentUnack;
  int sendNext;
  int sendWindow = TCPPacket.DEFAULT_WINDOW;

  /* last received seqNo + payloadLen*/
  int receiveNext;
  int receiveWindow = TCPPacket.DEFAULT_WINDOW;
  
  private IPStack ipStack;
  private NetworkInterface netInterface;
  private TCPListener tcpListener;
  
  long lastSendTime;
  
  private byte[] outgoingBuffer;
  int bufFirst = 0;
  int bufLast = 0;
  private TCPInputStream inputStream;
  private TCPOutputStream outputStream;
  
  TCPConnection(IPStack stack, NetworkInterface nIf) {
    ipStack = stack;
    netInterface = nIf;
  }
  
  public InputStream getInputStream() {
     if (inputStream == null) {
       inputStream = new TCPInputStream(this);
       /* steal the listener... */
       tcpListener = inputStream.listener;
     }
     return inputStream;
  }
  
  public OutputStream getOutputStream() {
    if (outputStream == null) {
      outputStream = new TCPOutputStream(this);
    }
    return outputStream;
  }
  
  public void setTCPListener(TCPListener listener) {
    if (tcpListener == null) {
      tcpListener = listener;
    } else {
      throw new IllegalStateException("TCPListener already set: " + tcpListener);
    }
  }
  
  public void newConnection(TCPConnection c) {
    if (tcpListener != null) {
      tcpListener.newConnection(c);
    }
  }
  
  /* check if incoming packet matches */
  public boolean matches(IPv6Packet packet, TCPPacket tcpPacket) {
    if ((externalPort == -1 || tcpPacket.sourcePort == externalPort) &&
        tcpPacket.destinationPort == localPort &&
        (localIP == null || Utils.equals(localIP, packet.destAddress)) &&
        (externalIP == null || Utils.equals(externalIP, packet.sourceAddress))) {
      return true;
    }
    return false;
  }

  /* send packet + update sendNext - this should take into account ext window */
  /* is this what mess up the stuff */
  public void send(TCPPacket tcpPacket) {
    IPv6Packet packet = new IPv6Packet(tcpPacket, localIP, externalIP);
    tcpPacket.seqNo = sendNext;
    if (tcpPacket.payload != null) {
      sendNext += tcpPacket.payload.length;
    }
    lastSendTime = System.currentTimeMillis();
    tcpPacket.printPacket(System.out);
    ipStack.sendPacket(packet, netInterface);
  }
  
  void receive(TCPPacket tcpPacket) {
    int plen = tcpPacket.payload == null ? 0 : tcpPacket.payload.length;

    if (tcpPacket.isAck()) {
      /* check if correct ack - we are only sending a packet a time... */
      if (sendNext == tcpPacket.ackNo) {
        /* no more unacked data */
        sentUnack = tcpPacket.ackNo;
        /* this means that we can send more data !!*/
      } else {
        System.out.println("TCPHandler: Unexpected ACK no: " +
            Integer.toString(tcpPacket.ackNo, 16) +
            " sendNext: " + Integer.toString(sendNext, 16));
      }
    }
    
    if (receiveNext == tcpPacket.seqNo) {
      //System.out.println("TCPHandler: data received ok!!!");
    } else {
      /* error - did we miss a packet??? */
      System.out.println("TCPHandler: seq error: expSeq: " +
          Integer.toString(receiveNext, 16) + " != seqNo: " +
          Integer.toString(tcpPacket.seqNo, 16));
    }

    /* update what to expect next - after this packet! */
    receiveNext = tcpPacket.seqNo + plen;
    
    if (tcpPacket.isFin()) {
      if (tcpListener != null && plen > 0) {
        /* notify app that the other side is closing... */
        tcpListener.connectionClosed(this);
      }
    }
    
    
    /* ack the new data! - this could be done from the connection itself!!*/
    TCPPacket tcpReply = TCPHandler.createAck(tcpPacket, 0);
    tcpReply.ackNo = tcpPacket.seqNo + plen;
    tcpReply.seqNo = sendNext;
    
    send(tcpReply);

    if (plen > 0)
    if (tcpListener != null) {
      tcpListener.tcpDataReceived(this, tcpPacket);
    }
  }

  public void send(byte[] bytes) {
    TCPPacket tcpPacket = createPacket();
    tcpPacket.payload = bytes;
    send(tcpPacket);
  }

  public void close() {
    if (state == ESTABLISHED) {
      TCPPacket packet = createPacket();
      packet.flags |= TCPPacket.FIN;
      state = FIN_WAIT_1;
      send(packet);
    }
  }
  
  public TCPPacket createPacket() {
    TCPPacket tcpPacket = new TCPPacket();
    tcpPacket.sourcePort = localPort;
    tcpPacket.destinationPort = externalPort;
    return tcpPacket;
  } 
}