 /**
 * Copyright (c) 2007, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * This file is part of MSPSim.
 *
 * $Id$
 *
 * -----------------------------------------------------------------
 *
 * MSP430
 *
 * Author  : Joakim Eriksson
 * Created : Sun Oct 21 22:00:00 2007
 * Updated : $Date$
 *           $Revision$
 */

package se.sics.mspsim.core;
import java.io.PrintStream;

import se.sics.mspsim.util.*;

public class MSP430 extends MSP430Core {

  public static final int RETURN = 0x4130;

  private int[] execCounter;

  private boolean debug = false;
  private boolean running = false;
  private long sleepRate = 50000;

  // Debug time - measure cycles
  private long lastCycles = 0;
  private long lastCpuCycles = 0;
  private long time;
  private long nextSleep = 0;
  private long nextOut = 0;

  private double lastCPUPercent = 0d;

  private long instCtr = 0;
  private DisAsm disAsm;

  private SimEventListener[] simEventListeners;

  /**
   * Creates a new <code>MSP430</code> instance.
   *
   */
  public MSP430(int type) {
    super(type);
    disAsm = new DisAsm();
    addChip(this);
  }

  public double getCPUPercent() {
    return lastCPUPercent;
  }

  public DisAsm getDisAsm() {
    return disAsm;
  }

  public void cpuloop() throws EmulationException {
    if (isRunning()) {
      throw new IllegalStateException("already running");
    }
    setRunning(true);
    // ??? - power-up  should be executed?!
    time = System.currentTimeMillis();
    run();
  }

  private void run() throws EmulationException {
    while (isRunning()) {
      // -------------------------------------------------------------------
      // Debug information
      // -------------------------------------------------------------------
      if (debug) {
	if (servicedInterrupt >= 0) {
	  disAsm.disassemble(reg[PC], memory, reg, servicedInterrupt);
	} else {
	  disAsm.disassemble(reg[PC], memory, reg);
	}
      }

      if (cycles > nextOut && !debug) {
	printCPUSpeed(reg[PC]);
	nextOut = cycles + 20000007;
      }

      if (emulateOP(-1)) {
	instCtr++;

	if (execCounter != null) {
	  execCounter[reg[PC]]++;
	}
      }

      /* Just a test to see if it gets down to a reasonable speed */
      if (cycles > nextSleep) {
	try {
	  Thread.sleep(10);
	} catch (Exception e) {
	}
	// Frequency = 100 * cycles ratio
	// Ratio = Frq / 100
	nextSleep = cycles + sleepRate;
      }

//       if ((instruction & 0xff80) == CALL) {
// 	System.out.println("Call to PC = " + reg[PC]);
//       }
    }
  }

  public long step() throws EmulationException {
    return step(0);
  }

  public long stepInstructions(int count) throws EmulationException {
    if (isRunning()) {
      throw new IllegalStateException("step not possible when CPU is running");
    }
    setRunning(true);
    // -------------------------------------------------------------------
    // Debug information
    // -------------------------------------------------------------------


    while (count-- > 0 && isRunning()) {
      if (debug) {
        if (servicedInterrupt >= 0) {
          disAsm.disassemble(reg[PC], memory, reg, servicedInterrupt);
        } else {
          disAsm.disassemble(reg[PC], memory, reg);
        }
      }

      boolean emuOP = emulateOP(-1);
      if (emuOP) {
        if (execCounter != null) {
          execCounter[reg[PC]]++;
        }
      }
    }
    setRunning(false);
    return cycles;
  }
  
  /* 
   * Perform a single step (even if in LPM) but no longer than to maxCycles + 1 instr
   */
  public long step(long maxCycles) throws EmulationException {
    if (isRunning()) {
      throw new IllegalStateException("step not possible when CPU is running");
    }

    // -------------------------------------------------------------------
    // Debug information
    // -------------------------------------------------------------------
    if (debug) {
      if (servicedInterrupt >= 0) {
	disAsm.disassemble(reg[PC], memory, reg, servicedInterrupt);
      } else {
	disAsm.disassemble(reg[PC], memory, reg);
      }
    }


    boolean emuOP = false;
    if (maxCycles > 0) {
      /* max one emulated instruction or maxCycles if in LPM */
      while (cycles < maxCycles && !(emuOP = emulateOP(maxCycles))) {
      }
    } else {
      int ctr = 0;
      while (!(emuOP = emulateOP(-1)) && ctr++ < 10000) {
        /* Stuck in LPM - hopefully not more than 10000 times*/
        }
    }

    if (emuOP) {
      if ((instCtr % 10000007) == 0 && !debug) {
	printCPUSpeed(reg[PC]);
      }

      if (execCounter != null) {
	execCounter[reg[PC]]++;
      }
    }
    return cycles;
}

  public void stop() {
    setRunning(false);
  }

  public int getExecCount(int address) {
    if (execCounter != null) {
      return execCounter[address];
    }
    return 0;
  }

  public void setMonitorExec(boolean mon) {
    if (mon) {
      if (execCounter == null) {
	execCounter = new int[MAX_MEM];
      }
    } else {
      execCounter = null;
    }
  }

  private void printCPUSpeed(int pc) {
    // Passed time
    int td = (int)(System.currentTimeMillis() - time);
    // Passed total cycles
    long cd = (cycles - lastCycles);
    // Passed "active" CPU cycles
    long cpud = (cpuCycles - lastCpuCycles);

    if (td == 0 || cd == 0) {
      return;
    }

    if (DEBUGGING_LEVEL > 0) {
      System.out.println("Elapsed: " + td
			 +  " cycDiff: " + cd + " => " + 1000 * (cd / td )
			 + " cyc/s  cpuDiff:" + cpud + " => "
			 + 1000 * (cpud / td ) + " cyc/s  "
			 + (10000 * cpud / cd)/100.0 + "%");
    }
    lastCPUPercent = (10000 * cpud / cd) / 100.0;
    time = System.currentTimeMillis();
    lastCycles = cycles;
    lastCpuCycles = cpuCycles;
    if (DEBUGGING_LEVEL > 0) {
      disAsm.disassemble(pc, memory, reg);
    }
  }

  public void generateTrace(PrintStream out) {
    if (profiler != null && out != null) {
      profiler.printStackTrace(out);
    }
  }
  
  public boolean getDebug() {
    return debug;
  }

  public void setDebug(boolean db) {
    debug = db;
  }

  public void setMap(MapTable map) {
    this.map = map;
    /* When we got the map table we can also profile! */
    if (profiler == null) {
      setProfiler(new SimpleProfiler());
      profiler.setCPU(this);
    }
  }

  public void setRunning(boolean running) {
    if (this.running != running) {
      this.running = running;
      SimEventListener[] listeners = this.simEventListeners;
      if (listeners != null) {
        SimEvent.Type type = running ? SimEvent.Type.START : SimEvent.Type.STOP;
        SimEvent event = new SimEvent(type);
        for(SimEventListener l : listeners) {
          l.simChanged(event);
        }
      }
    }
  }

  public boolean isRunning() {
    return running;
  }

  public long getSleepRate() {
    return sleepRate;
  }

  public void setSleepRate(long rate) {
    sleepRate = rate;
  }

  public synchronized void addSimEventListener(SimEventListener l) {
    simEventListeners = (SimEventListener[]) ArrayUtils.add(SimEventListener.class, simEventListeners, l);
  }

  public synchronized void removeSimEventListener(SimEventListener l) {
    simEventListeners = (SimEventListener[]) ArrayUtils.remove(simEventListeners, l);
  }
  
}
