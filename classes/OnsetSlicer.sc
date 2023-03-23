OnsetSlicer {

	// the single main analysis synthdef,
	// which need only be defined once for all instances
	classvar <def;

	// other class-scope constants
	classvar <outputDataFields;
	classvar <analysisTriggerFields;
	classvar <analysisTriggerCount;
	classvar <analysisTriggerMax;

	//---------------------------
	//--- behavior flags and options

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

	// total disk space budget per session
	var <>sessionMaxTotalDiskBytes;
	// total buffer duration budget per session
	var <>sessionMaxTotalBufferSamples;

	//---------------------------
	//--- important processing components

	var <server;
	var <synth;
	var <responder;

	//---------------------------
	//--- other runtime state

	var <isSessionRunning;
	var <sessionTotalDiskBytes;
	var <sessionTotalBufferSamples;

	// the rolling buffer
	var <captureBuf;
	var <captureBufFrames;

	// current frame of analysis results,
	// as reported directly from the synth
	var <rawDataList;
	// most recent analysis results, converted to a dictionary and timestamped/etc
	var <lastDataFrame;
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
		// list of the triggers sent from the analysis synth
		/// mostly redundant with output data fields, so that could probably be cleaned up
		analysisTriggerFields = [
			\position, // frame index in the capture buffer at onset time
			\duration,      // estimated initial non-silent duration, in seconds
			\audibleTime,   // total non-silent time in segment
			\amplitudeAvg,  // amplitude averaged over non-silent initial duration
			\amplitudeMax,  // maximum amplitude in segment
			\percentileAvg, // upper-percentile break frequency, averaged over non-silent control frames
			\centroidAvg,   // spectral magnitude centroid frequency, averaged over non-silent control frames
			\flatnessAvg,   // spectral flatness, averaged over non-silent control frames
			\flatnessMin,   // minimum spectral flatness in segment
		];
		analysisTriggerCount = analysisTriggerFields.size;
		analysisTriggerMax = analysisTriggerCount-1;

		// list of fields used in output data
		outputDataFields = [
			\id,         // identifier slug used in filenames (probably timstamp-derived)
			\time,       // time when onset trigger was logged
		] ++ analysisTriggerFields;
	}

	*new {
		arg server, inputBus, target, captureBufDur, addAction;
		^super.new.init(server, inputBus, target, captureBufDur, addAction);
	}

	init {
		arg aServer, aInputBus, aTarget=nil, aCaptureBufDur=nil, aAddAction=nil;
		var target;

		// 256MB, if i did my math right
		sessionMaxTotalDiskBytes = 268435456;

		// separate limit for buffers saved in RAM (similar size)
		sessionMaxTotalBufferSamples = 67108864;

		shouldWriteSessionBuffers = true;
		shouldKeepSessionBuffers = false;
		shouldWriteSessionData = true;
		shouldKeepSessionData = false;

		shouldTrimSilence = false;

		outputDataFormat = 0;

		isSessionRunning = false;

		segmentDoneCallback = { arg data; /* no-op */ };
		dataFrameCallback = { arg data; /* no-op */ };

		server = aServer;
		target = if(aTarget.notNil, {aTarget}, {server});

		captureBufFrames = server.sampleRate * if(aCaptureBufDur.isNil, {60}, {aCaptureBufDur});
		captureBuf = Buffer.alloc(server, captureBufFrames);

		rawDataList = Array.newClear(analysisTriggerCount);
		lastDataFrame = nil;

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
			var timeStamp = Date.getDate.format("%Y%m%d_%H%M%S");

			var path = if (outputFolderPath.isNil, {
				Platform.userHomeDir ++ "/dust/data/edward/output/" ++  timeStamp
			}, {
				outputFolderPath
			});

			var ext = switch(outputDataFormat,
				{0}, {".csv"},
				{1}, {".scd"},
				{2}, {".lua"}
			);

			if (PathName(path).isFolder.not, {
				File.mkdir(path);
			});

			outputSampleFolder = path ++ "/sliced";
			postln("outputSampleFolder: " ++ outputSampleFolder);
			if (PathName(outputSampleFolder).isFolder.not, {
				File.mkdir(outputSampleFolder);
			});

			path = path ++ "/" ++ timeStamp ++ ext;
			outputDataFile = File.open(path, "a");
			this.writeOutputDataHeader;
		});

		sessionData = Dictionary.new;
		sessionBuffers.do({ arg buf; buf.free; });
		sessionBuffers = Dictionary.new;
		sessionTimeStamps = Set.new;

		rawDataList = Array.newClear(analysisTriggerCount);
		lastDataFrame = nil;
		timeLast = SystemClock.seconds;

		isSessionRunning = true;

		sessionTotalDiskBytes = 0;
		sessionTotalBufferSamples = 0;
	}

	// immediately end the session and close the analysis results file.
	// current segment will be discarded!
	endSession {
		if (isSessionRunning, {
//			var path  = outputDataFile.path;
			this.writeOutputDataFooter;
			outputDataFile.close;
			postln("stopped session"); //; output data file: " ++ path ++ "; size = " ++ File.fileSize(path)++"B");
			isSessionRunning = false;
		}, {
			postln("(OnsetSlicer: session is already stopped)");
		});
	}


	//--- "private" methods

	/// this will fire on each trigger message from `SendTrig`
	handleOnsetTrigger {
		arg time, id, value;

		if (rawDataList[id].notNil, {
			postln("WARNING: corrupted data frame in OnsetSlicer");
		});

		rawDataList[id] = value;

		// bad hack: assuming magic number of ids, assuming last id arrives last
		if (id == analysisTriggerMax, {
			var dataFrame = OnsetSlicer.buildDataFrame(rawDataList);
			dataFrame[\time] = time;
			analysisTriggerCount.do({ arg i; rawDataList[i] = nil; });
			this.handleDataFrame(dataFrame);
		});

	}

	/// this will fire on each complete "dataframe" (onset plus analysis)
	handleDataFrame {
		arg data;
		var pos = data[\position];

		dataFrameCallback.value(data);

		if (isSessionRunning, {
			if(lastDataFrame.notNil, {
				// save a copy of the previous data so next onset doesn't stomp it
				var data0 = lastDataFrame.copy;

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
						var bytes;
						tmpBuf.write(path, "wav");
						server.sync;
						bytes = File.fileSize(path);
						postln("wrote audio file: " ++ path ++ "; size = "++bytes);
						sessionTotalDiskBytes = sessionTotalDiskBytes + bytes;
					});

					/// optionally, save the slice buffer in RAM
					if (shouldKeepSessionBuffers, {
						sessionBuffers[timeStamp] = tmpBuf;
						sessionTotalBufferSamples = sessionTotalBufferSamples + durFrames;
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


					// stop the session if we've busted the disk/ram limits
					if (sessionTotalBufferSamples > sessionMaxTotalBufferSamples, {
						postln("total samples in RAM exceeds limit: "
							++ sessionTotalBufferSamples ++ "; ending session");
						this.endSession;
					});
					if(sessionTotalDiskBytes > sessionMaxTotalDiskBytes, {
						postln("total bytes on disk exceeds limit: "
							++ sessionTotalDiskBytes ++ "; ending session");
						this.endSession;
					});


				}.play;
			});
		});
		lastDataFrame = data;
	}

	makeTimeStamp {
		var stamp = Date.getDate.format("%Y%m%d_%H%M%S");
		if(sessionTimeStamps.includes(stamp), {
			var offset = 1;
			var newStamp = stamp ++ "_" ++ offset;
			while ({sessionTimeStamps.includes(newStamp)}, {
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
		outputDataFile.write("" ++ data[\time] ++ ", ");
		analysisTriggerFields.do({ arg key;
			outputDataFile.write("" ++ data[key] ++ ", ");
		});
		outputDataFile.write("\n");
	}

	//-------------------
	//--- class methods

	*buildDataFrame { arg rawDataList;
		var df = Dictionary.new;
		analysisTriggerCount.do({ arg i;
			df[analysisTriggerFields[i]] = rawDataList[i];
		});
		^df
	}

	/// signal analysis is completely defined in one large synthdef
	*sendSynthDef { arg aServer;

		def = SynthDef.new(\OnsetSlicer_multisend, {
			var input = In.ar(\in.kr);

			/// --- onset analysis
			var chain = FFT(LocalBuf(\fftSize.ir(2048)), input, \hop.ir(0.5), \wintype.ir(0), \active.kr(1), \winsize.ir(0));
			var onsets = Onsets.kr(chain,
				\threshold.kr(0.5), \odftype.kr('rcomplex'),
				\relaxtime.kr(1), \floor.kr(0.1), \mingap.kr(10), \medianspan.kr(11),
				\whtype.kr(1), \rawodf.kr(0)
			);

			/// --- delayed recording buffer
			var recordBuf= \buf.kr;
			var writePos = Phasor.ar(end:BufFrames.kr(recordBuf));
			var delayBuf = LocalBuf(0x10000); // >1sec, 2^n
			var delayed = BufDelayC.ar(delayBuf, input, \lookahead.kr(0.014));
			var bufWrite = BufWr.ar(delayed, recordBuf, writePos);
			var startPos = Latch.kr(A2K.kr(writePos), onsets);

			/// --- more analysis!

			// bit of a hack to make a resettable accumulator
			var leak = if(onsets, 0, 1);

			// amplitude follower
			// fast attack and release since this is used for statistical filtering
			// (not an envelope we would listen to or use directly)
			// still want some smoothing for low frequency input
			var amp = Amplitude.kr(input, \ampAttack.kr(0.005), \ampRelease.kr(0.01));
			var ampMax = RunningMax.kr(amp, onsets);

			var isAudible = amp > \audibleThreshold.kr(-60.dbamp);
			var ampSum = Integrator.kr(amp, leak);
			var audibleCount = Integrator.kr(isAudible, leak);

			/// separate amp follower and gate for estimating initial non-silent duration after the onset
			/// this should use a very slow release time in comparison
			/// NB: this release time will effectively set a lower bound on the segment size
			var isQuiet = (Amplitude.kr(input, \quietAttack.kr(0.005), \quietRelease.kr(0.1),) > \quietThreshold.kr(-60.dbamp)).if(0, 1);
			var duration = Latch.kr(Sweep.kr(onsets, 1), SetResetFF.kr(isQuiet, onsets));

			/// all following parameters are gated by amplitude before accumulator
			var pcile = SpecPcile.kr(chain, \fraction.kr(0.9));
			var pcileSum = Integrator.kr(if(isAudible, pcile.cpsmidi, 0), leak);

			var centroid = SpecCentroid.kr(chain);
			var centroidSum = Integrator.kr(if(isAudible, centroid.cpsmidi, 0), leak);

			var flatness = SpecFlatness.kr(chain);
			var flatnessSum = Integrator.kr(if(isAudible, flatness, 0), leak);
			var flatnessMin = RunningMin.kr(flatness, onsets);

			/// --- send everything to sclang

			/// NB: i wish SendTrig could send arrays but nope.
			/// let me know of any better way to do this!
			/// (while ensuring that these things are all calculated on the same control block)

			/// NB: in contrast to initial demo, i'm just hardcoding these IDs
			/// (making them settable doesn't help with magic-number problems, just more verbose)
			SendTrig.kr(onsets, 0, startPos);
			SendTrig.kr(onsets, 1, duration);
			SendTrig.kr(onsets, 2, audibleCount*ControlDur.ir);
			SendTrig.kr(onsets, 3, ampSum/duration);
			SendTrig.kr(onsets, 4, ampMax);
			SendTrig.kr(onsets, 5, pcileSum/audibleCount);
			SendTrig.kr(onsets, 6, centroidSum/audibleCount);
			SendTrig.kr(onsets, 7, flatnessSum/audibleCount);
			SendTrig.kr(onsets, 8, flatnessMin);

		});
		def.send(aServer);
	}
}