OnsetSlicer {

	// the single main analysis synthdef,
	// which need only be defined once for all instances
	classvar <def;

	//--- behavior flags
	// set to keep sliced buffers in memory as well as on disk
	var <>shouldKeepSessionBuffers;
	// set to keep the analysis datain memory as well as on disk
	var <>shouldKeepSessionData;
	// set for each sliced buffer/file to be trimmed to estimated initial non-silence
	var <>shouldTrimSilence;

	// runtime elements
	var <server;
	var <synth;
	var <responder;

	// the rolling buffer
	var <captureBuf;
	var <captureBufFrames;

	// the current and previous frame of analysis results
	var <dataFrame;
	var <dataFrameLast;

	// sliced buffers from current session, a Dictionary keyed by timestamp
	// should be empty if `shouldKeepSessionBuffers` is false
	var <sessionBuffers;
	// session data frames, a Dictionary keyed by timestamp
	// should be empty if `shouldKeepSessionData` is false
	var <sessionData;
	// always save the set of timestamps in the current session,
	// so we can check for collisions
	var <sessionTimeStamps;

	// paths for disk output
	var <outputDataFile;
	var <outputSampleFolder;

	//--- callback functions
	// fired when a complete dataframe is received (even when not in a session)
	// could be useful for tuning detection parameters
	var <>dataFrameCallback;

	// fired when segment is written to disk
	var <>segmentDoneCallback;

	var <isSessionRunning;

	*new {
		arg aServer;
		^super.new.init(aServer);
	}

	init {
		arg aServer, aInputBus, aTarget=nil, aCaptureBufDur=nil, aAddAction=nil;
		var target;

		shouldKeepSessionBuffers = false;
		shouldTrimSilence = true;
		isSessionRunning = false;

		segmentDoneCallback = { arg data; /* no-op */ };
		dataFrameCallback = { arg data; /* no-op */ };

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
		sessionData = Dictionary.new;
		sessionBuffers.do({ arg buf; buf.free; });
		sessionBuffers = Dictionary.new;
		sessionTimeStamps = Set.new;
		dataFrameLast = nil;
	}

	endSession {
		// write everything to disk
	}


	//--- "private" methods

	/// this will fire on the client on each trigger message from `SendTrig`
	handleOnsetTrigger {
		arg time, id, value;
		//[time, id, value].postln;
		dataFrame[id] = value;
		// bad hack: assuming magic number of ids, assuming last id arrives last
		if (id == 6, {
			if (dataFrame.indexOf(nil).notNil, {
				postln("whoops, incomplete data frame");
			}, {
				// TODO: would be smart to save timestamps from each trigger,
				// and compare them here
				this.handleDataFrame.value(time, Dictionary.newFrom([
					\position, dataFrame[0],
					\duration, dataFrame[1],
					\amplitude, dataFrame[2],
					\count, dataFrame[3],
					\percentile, dataFrame[4],
					\centroid, dataFrame[5],
					\flatness, dataFrame[6],
				]));
				7.do({ arg i; dataFrame[i] = nil; });
			});
		});

	}

	/// this will fire on each complete "dataframe" (onset plus analysis)
	handleDataFrame {
		arg time, data;
		var pos = data[\position];

		dataFrameCallback.value(time, data);

		// data.keys.do({ arg k; postln("" ++ k ++ " = " ++ data[k]) });

		if (isSessionRunning, {
			if(dataFrameLast.notNil, {
				// save the previous data so next onset doesn't stomp it
				var data0 = ~last_data;
				Routine {
					var tmp, dur, pos, pos0, durFrames, outputPath, dataFile;
					dur = data[\duration];
					durFrames = dur * server.sampleRate;

						// TODO: continue refactor...

					// /// FIXME: don't use the estimated duration for this...?
					// if (dur > captureBuf.duration, {
					// 	postln("time since last onset exceeds buffer duration; skipping output");
					// 	}, {


							// postln("allocating buffer; frames = " ++ durFrames);
							// tmp = Buffer.alloc(server, durFrames);
							// server.sync;
							//
							// /// FIXME: could add some pre/postroll here.
							// /// (one possible motivator is that the position reporting will have some jitter,
							// /// due to AR/KR and analysis latency)
							// pos = data[\position];
							// pos0 = data0[\position];
							//
							// /// FIXME: now i actually think i would like the option to save each entire segment between onsets.
							// /// (we can always save the estimated duration in the data, but don't always want to truncate by it)
							// if (pos > pos0, {
							// 	captureBuf.copyData(tmp, srcStartAt:pos0, numSamples:durFrames);
							// 	}, {
							// 		// wrapped the buffer: perform 2x copy steps
							// 		var n = captureBuf.size - pos0;
							// 		captureBuf.copyData(tmp, srcStartAt:pos0, numSamples:n);
							// 		captureBuf.copyData(tmp, dstStartAt:n, numSamples:(durFrames-n));
							// });
							// server.sync;


						/// FIXME
						// outputPath = ~get_output_paths.value(time, data);
						// tmp.write(outputPath[0], headerFormat:"wav");
						// server.sync;

						segmentDoneCallback.value(outputPath);


						/// FIXME: would be nicer to append to a single data file
						/*					dataFile = File(outputPath[1], "w");
						dataFile.write("duration, amplitude, count, percentile, centroid, flatness,\n");
						dataFile.write(""
						++ data[\duration] ++ ", "
						++ data[\amplitude] ++ ", "
						++ data[\count] ++ ", "
						++ data[\percentile] ++ ", "
						++ data[\centroid] ++ ", "
						++ data[\flatness]);
						dataFile.close;*/
//					});
				}.play;
			});
		});
		dataFrameLast = data;
	}

	makeSlicedBuffer {
		arg start, end, dur;
		if (shouldTrimSilence, {
			// TODO
		}, {
			// TODO
		});

	}

	makeTimeStamp {
		var stamp = Date.getDate.format("%Y%m%d_%H%M%S");
		if(sessionTimeStamps.contains(stamp), {
			var offset = 1;
			var newStamp = stamp ++ "_" ++ offset;
			while (sessionTimeStamps.contains(newStamp), {
				offset = offset + 1;
				newStamp = stamp ++ "_" ++ offset;
			});
			stamp = newStamp;
		});
		^stamp.asSymbol;
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

}