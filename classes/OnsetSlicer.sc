OnsetSlicer {
	classvar <def;
	var <server;
	var <synth;
	var <responder;
	var <captureBuf;
	var <captureBufFrames;
	var <dataFrame;
	var <sampleBufList;

	// user-defined callback function, fired when segment completes
	var <segmentCallback;

	*new {
		arg aServer;
		^super.new.init(aServer);
	}

	init {
		arg aServer, aInputBus, aTarget=nil, aCaptureBufDur=nil, aAddAction=nil;
		var target;
		server = aServer;
		target = if(aTarget.notNil, {aTarget}, {server});
		captureBufFrames = server.sampleRate * if(aCaptureBufDur.isNil, {60}, {aCaptureBufDur});
		captureBuf = Buffer.alloc(captureBufFrames);
		synth = Synth.new([
			\in, aInputBus
		], \target, addAction:if(aAddAction.isNil, {\addToTail}, {aAddAction}));

		server.sync;


		responder = OSCFunc({ arg msg, time;
		
		var node = msg[1];
		var id = msg[2];
		var value = msg[3];
		if (node == synth.nodeID, {
			this.handleOnsetTrigger(time, id, value);
		});
	},'/tr', server.addr);
	}

	startSession { 
		// clear the buffers
	}

	endSession { 

	}

	freeSampleBuffers { 
		
	}

	setSynthParam {
		arg key, value;
		synth.set(key, value);
	}

	/// signal analysis is completely defined in one large synthdef
	*sendSynthDef { arg aServer;

		def = SynthDef.new(\onset_analysis_multisender, {
			var input = In.ar(\in.kr);

			/// analysis
			var chain = FFT(LocalBuf(\fftSize.ir(2048)), input, \hop.ir(0.5), \wintype.ir(0), \active.kr(1), \winsize.ir(0));
			var onsets = Onsets.kr(chain,
				\threshold.kr(0.5), \odftype.kr('rcomplex'),
				\relaxtime.kr(1), \floor.kr(0.1), \mingap.kr(10), \medianspan.kr(11),
				\whtype.kr(1), \rawodf.kr(0)
			);

			/// delayed recording buffer
			var recordBuf= \buf.kr;
			var writePos = Phasor.ar(end:BufFrames.kr(recordBuf));
			var delayBuf = LocalBuf(0x10000); // >1sec, 2^n
			var delayed = BufDelayC.ar(delayBuf, input, \lookahead.kr(0.014));
			var bufWrite = BufWr.ar(delayed, recordBuf, writePos);
			var startPos = Latch.kr(A2K.kr(writePos), onsets);

			/// additional analysis parameters
			/// each is accumulated so we can report averages

			// bit of a hack to make a resettable accumulator
			var leak = if(onsets, 0, 1);

			var amp = Amplitude.kr(input, \ampAttack.kr(0.005), \ampRelease.kr(0.01));
			var gate = amp > \gateThreshold.kr(-60.dbamp);
			var ampSum = Integrator.kr(amp, leak);
			var gateCount = Integrator.kr(gate, leak);

			//// record duration for non-silent only
			// use a much slower release here
			var isQuiet = (Amplitude.kr(input, \quietAttack.kr(0.005), \quietRelease.kr(1),) > \quietThresh.kr(-60.dbamp)).if(0, 1);

			var quietTrig = SetResetFF.kr(isQuiet, onsets);
			var duration = Latch.kr(Sweep.kr(onsets, 1), quietTrig);

			/// all following parameters are gated by amplitude before accumulator
			var pcile = SpecPcile.kr(chain, \fraction.kr(0.9));
			var pcileSum = Integrator.kr(if(gate, pcile.cpsmidi, 0), leak);
			var centroid = SpecCentroid.kr(chain);
			var centroidSum = Integrator.kr(if(gate, centroid.cpsmidi, 0), leak);
			var flatness = SpecFlatness.kr(chain);
			var flatnessSum = Integrator.kr(if(gate, flatness, 0), leak);

			SendTrig.kr(quietTrig, \idPos.ir(0), startPos);
			SendTrig.kr(quietTrig, \idDur.ir(1), duration);
			SendTrig.kr(quietTrig, \idAmpAvg.ir(2), ampSum/duration);
			SendTrig.kr(quietTrig, \idCount.ir(3), gateCount);
			SendTrig.kr(quietTrig, \idPcileAvg.ir(4), pcileSum/gateCount);
			SendTrig.kr(quietTrig, \idCentroidAvg.ir(5), centroidSum/gateCount);
			SendTrig.kr(quietTrig, \idFlatnessAvg.ir(6), flatnessSum/gateCount);

			/// TODO: add running maxima?

		});
		def.send(aServer);
		
	}


	//--- "private" methods

	/// this will fire on the client on each trigger message from `SendTrig`
	handleOnsetTrigger {
		arg time, id, value;
		
	}

	/// this will fire on each complete "dataframe" (onset plus analysis)
	handleDataFrame {
		arg time, data;
	}

}