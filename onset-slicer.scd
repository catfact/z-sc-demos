/*
this demo takes an audio input and segments it by onsets in real time, storing each segment to disk.
for each segment, a .wav file is rendered to disk alongside a .csv file describing some analysis parameters.
these parameters should help efforts to organize segments by "brightness" and "tonality,"
in addition to the more obvious amplitude and duration.

all
analysis parameters
- duration: duration of non-quiet audio portion since previous onset
- amplitude: average amplitude over non-quiet portion
- percentile: frequency below which R% of energy resides (default is 95%)
- centroid: another measure of brightness
- flatness: a measure of (non-)tonality

the spectral parameters are gated by amplitude before averaging.
in other words, each average is taken over the set of all samples above an amplitude threshold.

many improvements are possible! a few that come to mind:
- add running maxima for parameters
- factor into a class for easier inclusion on norns, add configuration methods etc
- append rows to single analysis file, instead of 1 file per segment
*/

Routine {

	SynthDef.new(\onset_analysis_multisender, {
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

	//	duration.poll;
	//	gateCount.poll;

		/// TODO: add running maxima

	}).send(s);
	s.sync;


	b = Bus.audio(s, 1);
	~input = { Out.ar(b.index, SoundIn.ar(0)) }.play(s);

	~capture_buf_frames = s.sampleRate * 32.0;
	~capture_buf = Buffer.alloc(s, ~capture_buf_frames);

	z = Synth.new(\onset_analysis_multisender, [\in, b.index]);
	z.set(\threshold, 0.25);
	z.set(\odftype, \wphase);

	s.sync;

	~onsets_id = z.nodeID;

	o = OSCFunc({ arg msg, time;
		// [time, msg].postln;
		var node = msg[1];
		var id = msg[2];
		var value = msg[3];
		if (node == ~onsets_id, {
			~handle_onset_trigger.value(time, id, value);
		});
	},'/tr', s.addr);


	// kinda hacky, would be nice if `SendTrig` could send an array (can it?)
	~data_frame = Array.newClear(7);

	~handle_onset_trigger = {
		arg time, id, value;
		//[time, id, value].postln;
		~data_frame[id] = value;
		// bad hack: assuming magic number of ids, assuming last id arrives last
		if (id == 6, {
			if (~data_frame.indexOf(nil).notNil, {
				postln("whoops, incomplete data frame");
			}, {
				// TODO: would be smart to check here that timestamps are reasonable
				~handle_data.value(time, Dictionary.newFrom([
					\position, ~data_frame[0],
					\duration, ~data_frame[1],
					\amplitude, ~data_frame[2],
					\count, ~data_frame[3],
					\percentile, ~data_frame[4],
					\centroid, ~data_frame[5],
					\flatness, ~data_frame[6],
				]));
				6.do({ arg i; ~data_frame[i] = nil; });
			});
		});
	};

	~last_data = nil;
	~handle_data = { arg time, data;


		/// FIXME: idk about the threading structure here.
		/// it seems like a good idea to make a dedicated worker thread
		/// for all temp buffer allocation, freeing, copying and disk output,
		/// and make this handler add a job to a linked-list job queue.
		//
		// for now, spawn a new worker for each segment and cross our fingers
		var pos = data[\position];

		data.keys.do({ arg k; postln("" ++ k ++ " = " ++ data[k]) });

		if(~last_data.notNil, {
			// save the previous data so next onset doesn't stomp it
			var data0 = ~last_data;
			Routine {
				var tmp, dur, pos, pos0, durFrames, outputPath, dataFile;
				dur = data[\duration];
				durFrames = dur * s.sampleRate;
				if (dur > ~capture_buf.duration, {
					postln("time since last onset exceeds buffer duration; skipping output");
				}, {
					postln("allocating buffer; frames = " ++ durFrames);
					tmp = Buffer.alloc(s, durFrames);
					s.sync;

					/// FIXME: could add some pre/postroll here.
					/// (one possible motivator is that the position reporting will have some jitter,
					/// due to AR/KR and analysis latency)
					pos = data[\position];
					pos0 = data0[\position];

					/// FIXME: now i actually think i would like the option to save each entire segment between onsets.
					/// (we can always save the estimated duration in the data, but don't always want to truncate by it)
					if (pos > pos0, {
						~capture_buf.copyData(tmp, srcStartAt:pos0, numSamples:durFrames);
					}, {
						// wrapped the buffer: perform 2x copy steps
						var n = ~capture_buf.size - pos0;
						~capture_buf.copyData(tmp, srcStartAt:pos0, numSamples:n);
						~capture_buf.copyData(tmp, dstStartAt:n, numSamples:(durFrames-n));
					});
					s.sync;

					outputPath = ~get_output_paths.value(time, data);
					tmp.write(outputPath[0], headerFormat:"wav");
					s.sync;
					tmp.free;

					/// FIXME: would be nicer to append to a single data file
					dataFile = File(outputPath[1], "w");
					dataFile.write("duration, amplitude, count, percentile, centroid, flatness,\n");
					dataFile.write(""
						++ data[\duration] ++ ", "
						++ data[\amplitude] ++ ", "
						++ data[\count] ++ ", "
						++ data[\percentile] ++ ", "
						++ data[\centroid] ++ ", "
						++ data[\flatness]);
					dataFile.close;
				});
			}.play;
		});
		~last_data = data;
	};

	~get_output_paths = { arg time, data;
		var path = PathName(Document.current.path).pathOnly.asString;
		path = path ++ "onset-slicer-output/" ++ time.floor.asInteger.asString;
		[
			path ++ ".wav",
			path ++ ".csv",
		]
	};

}.play;