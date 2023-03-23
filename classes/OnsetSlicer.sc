OnsetSlicer {

	// the single main analysis synthdef,
	// which need only be defined once for all instances
	classvar <def;
	classvar <outputDataFields;

	//---------------------------
	//--- behavior flags

	// set to keep sliced buffers in memory / on disk
	var <>shouldKeepSessionBuffers;
	var <>shouldWriteSessionBuffers;
	// set to keep the analysis data in memory / on disk
	var <>shouldKeepSessionData;
	var <>shouldWriteSessionData;

	// set for each sliced buffer/file to be trimmed to estimated initial non-silence
	var <>shouldTrimSilence;

	// numerical index for output data format
	// 0: .csv (default)
	// 1: .scd
	// 2: .lua
	var <>outputDataFormat;

	//---------------------------
	//--- important processing components

	var <server;
	var <synth;
	var <responder;

	//---------------------------
	//--- other runtime state

	var <isSessionRunning;

	// the rolling buffer
	var <captureBuf;
	var <captureBufFrames;

	// the current and previous frame of analysis results
	var <dataFrame;
	var <dataFrameLast;
	var <timeLast;

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

	//---------------------------
	//--- callback functions

	// fired when a complete dataframe is received (even when not in a session)
	// could be useful for tuning detection parameters
	var <>dataFrameCallback;

	// fired when segment is written to disk
	var <>segmentDoneCallback;


	//=============================================
	//=== methods

	*initClass {
		outputDataFields = [
			\id,         // timestamp slug
			\time,       // time when onset trigger was loged
			\duration,   // estimated initial non-silent duration, in seconds
			\count,      // count of non-silent control frames
			\amplitude,  // amplitude averaged over non-silent initial duration
			\percentile, // upper-percentile break frequency, averaged over non-silent control frames
			\centroid,   // spectral magnitude centroid frequency, averaged over non-silent control frames
			\flatness,   // spectral flatness, averaged over non-silent control frames
		];
	}

	*new {
		arg server, inputBus, target, captureBufDur, addAction;
		^super.new.init(server, inputBus, target, captureBufDur, addAction);
	}

	init {
		arg aServer, aInputBus, aTarget=nil, aCaptureBufDur=nil, aAddAction=nil;
		var target;

		shouldWriteSessionBuffers = true;
		shouldKeepSessionBuffers = false;
		shouldWriteSessionData = true;
		shouldKeepSessionData = false;

		shouldTrimSilence = false;

		outputDataFormat = 0;
		outputSampleFolder =

		isSessionRunning = false;

		segmentDoneCallback = { arg data; /* no-op */ };
		dataFrameCallback = { arg data; /* no-op */ };

		server = aServer;
		target = if(aTarget.notNil, {aTarget}, {server});

		captureBufFrames = server.sampleRate * if(aCaptureBufDur.isNil, {60}, {aCaptureBufDur});
		captureBuf = Buffer.alloc(server, captureBufFrames);

		dataFrame = Array.newClear(7);

		synth = Synth.new(\OnsetSlicer_multisend, [
			\in, aInputBus
		], target:target, addAction:if(aAddAction.isNil, {\addToTail}, {aAddAction}));

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

	startSession {  arg outputFolderPath;
		if (shouldWriteSessionData, {
			/// FIXME (minor): repeating this format string
			var timeStamp = Date.getDate.format("%Y%m%d_%H%M%S");

			var path = if (outputFolderPath.isNil, {
				Platform.userHomeDir ++ "/" ++  timeStamp
			}, {
				outputFolderPath
			});

			var ext = switch(outputDataFormat,
				{0}, {".csv"},
				{0}, {".scd"},
				{0}, {".lua"}
			);

			if (PathName(path).isFolder.not, {
				File.mkdir(path);
			});

			path = path ++ "/" ++ timeStamp ++ ext;
			outputDataFile = File.open(path, "a");
			this.writeOutputDataHeader;
		});

		sessionData = Dictionary.new;
		sessionBuffers.do({ arg buf; buf.free; });
		sessionBuffers = Dictionary.new;
		sessionTimeStamps = Set.new;

		dataFrame = Array.newClear(7);
		dataFrameLast = nil;
		timeLast = SystemClock.seconds;

		isSessionRunning = true;
	}

	endSession {
		outputDataFile.close;
		isSessionRunning = false;
	}


	//--- "private" methods

	/// this will fire on each trigger message from `SendTrig`
	handleOnsetTrigger {
		arg time, id, value;
		dataFrame[id] = value;

		// bad hack: assuming magic number of ids, assuming last id arrives last
		if (id == 6, {
			if (dataFrame.indexOf(nil).notNil, {
				postln("whoops, incomplete data frame");
			}, {
				// TODO: would be smart to save timestamps from each trigger,
				// and compare them here
				this.handleDataFrame(Dictionary.newFrom([
					\time, time,
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
		arg data;
		var pos = data[\position];

		dataFrameCallback.value(data);

		if (isSessionRunning, {
			if(dataFrameLast.notNil, {
				// save the previous data so next onset doesn't stomp it
				var data0 = dataFrameLast;
				postln("handling data frame: " ++ data0);
				Routine {
					var durFrames;
					var pos0 = data0[\position];
					var pos1 = data[\position];
					var elapsedTime = data[\time] - data0[\time];
					var wrapped = pos0 >= pos1;
					var tmpBuf, tmpFrames;
					var timeStamp;

					/// determine duration of slice buffer
					if (shouldTrimSilence, {
						// use estimated non-silent duration only
						durFrames = (data[\duration] * server.sampleRate).min(captureBufFrames);
					}, {
						// use the total length of the segment
						if (elapsedTime >= captureBuf.duration, {
							durFrames = captureBuf.numFrames;
						}, {
							durFrames = if(wrapped, {
								captureBufFrames- (pos1 - pos0);
							}, {
								pos1 - pos0;
							});
						});
					});

					postln("duration in frames: " ++ durFrames);

					/// allocate and copy to the slice buffer
					tmpBuf = Buffer.alloc(server, durFrames);
					server.sync;
					tmpFrames = durFrames.min(captureBufFrames - pos0);
					captureBuf.copyData(tmpBuf, srcStartAt:pos0, numSamples:tmpFrames);
					durFrames = durFrames - tmpFrames;
					if (durFrames > 0, {
						captureBuf.copyData(tmpBuf, dstStartAt:pos0 + tmpFrames, numSamples:(durFrames-tmpFrames));
					});

					// get a timestamp to use as segment/buffer ID
					timeStamp = this.makeTimeStamp;

					/// optionally, write the slice buffer to disk
					if (shouldWriteSessionBuffers, {
						var path = outputSampleFolder ++ "/" ++ timeStamp ++ ".wav";
						tmpBuf.write(path, "wav");
					});

					/// optionally, save the slice buffer in RAM
					if (shouldKeepSessionBuffers, {
						sessionBuffers[timeStamp] = tmpBuf;
					}, {
						tmpBuf.free;
					});

					/// optionally, save analysis data in RAM
					if (shouldKeepSessionData, {
						sessionData[timeStamp] = data;
					});

					/// optionally, write analysis data to disk
					if (shouldWriteSessionData, {
						this.writeOutputDataRow(timeStamp, data);
					});

					/// fire the user segment callback
					server.sync;
					segmentDoneCallback.value(timeStamp, data);
				}.play;
			});
		});
		dataFrameLast = data;
	}

	makeTimeStamp {
		var stamp = Date.getDate.format("%Y%m%d_%H%M%S");
		if(sessionTimeStamps.includes(stamp), {
			var offset = 1;
			var newStamp = stamp ++ "_" ++ offset;
			while (sessionTimeStamps.includes(newStamp), {
				offset = offset + 1;
				newStamp = stamp ++ "_" ++ offset;
			});
			stamp = newStamp;
		});
		sessionTimeStamps.add(stamp);
		^stamp.asSymbol;
	}

	writeOutputDataHeader {
		switch(outputDataFormat,
			{0}, { // csv
				// nothing to do
			},
			{1}, { // scd
				outputDataFile.write("[ // ");

			},
			{2}, { // lua
				outputDataFile.write("{ -- ");
			}
		);

		outputDataFields.do({ arg key;
			outputDataFile.write(key ++ ", ");
		});
		outputDataFile.write("\n");
	}

	writeOutputDataFooter {
		switch(outputDataFormat,
			{0}, { // csv
				// nothing to do
			},
			{1}, { // scd
				outputDataFile.write("]");
			},
			{2}, { // lua
				outputDataFile.write("}");
			}
		);
		outputDataFile.write("\n");
	}




	writeOutputDataRow { arg timeStamp, data;
		outputDataFile.write(timeStamp ++ ", ");
		outputDataFields.do({ arg key;
			outputDataFile.write("" ++ data[key] ++ ", ");
		});
		outputDataFile.write("\n");
	}

	/// signal analysis is completely defined in one large synthdef
	*sendSynthDef { arg aServer;

		def = SynthDef.new(\OnsetSlicer_multisend, {
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

			SendTrig.kr(onsets, \idPos.ir(0), startPos);
			SendTrig.kr(onsets, \idDur.ir(1), duration);
			SendTrig.kr(onsets, \idAmpAvg.ir(2), ampSum/duration);
			SendTrig.kr(onsets, \idCount.ir(3), gateCount);
			SendTrig.kr(onsets, \idPcileAvg.ir(4), pcileSum/gateCount);
			SendTrig.kr(onsets, \idCentroidAvg.ir(5), centroidSum/gateCount);
			SendTrig.kr(onsets, \idFlatnessAvg.ir(6), flatnessSum/gateCount);

			/// TODO: add running maxima?

		});
		def.send(aServer);

	}

}