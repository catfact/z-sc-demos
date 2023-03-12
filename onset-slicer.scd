
Routine {

	SynthDef.new(\amp_ons, {
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
		var delayed = BufDelayC.ar(delayBuf, input, \lookahead.kr(0.025));
		var bufWrite = BufWr.ar(delayed, recordBuf, writePos);

		/// additional analysis parameters
		var duration = Timer.kr(onsets);
		var durationFrames = duration * ControlRate.ir;

		/// each is accumulated so we can report averages
		// bit of a hack to make a resettable accumulator
		var leak = if(onsets, 0, 1);
		var amp = Amplitude.kr(input);
		var ampSum = Integrator.kr(amp, leak);
		var pcile = SpecPcile.kr(chain, \fraction.kr(0.95));
		var pcileSum = Integrator.kr(pcile, leak);
		var centroid = SpecCentroid.kr(chain);
		var centroidSum = Integrator.kr(centroid, leak);
		var gatedFlatness = SpecFlatness.kr(chain) * amp > \flatnessGateThresh.kr(0.05);
		var gatedFlatnessSum = Integrator.kr(gatedFlatness, leak);

		SendTrig.kr(onsets, \idPos.ir(0), A2K.kr(writePos));
		SendTrig.kr(onsets, \idDur.ir(1), duration);
		SendTrig.kr(onsets, \idAmpAvg.ir(2), ampSum/durationFrames);
		SendTrig.kr(onsets, \idPcileAvg.ir(3), pcileSum/durationFrames);
		SendTrig.kr(onsets, \idCentroidAvg.ir(4), centroidSum/durationFrames);
		SendTrig.kr(onsets, \idGatedFlatnessAvg.ir(5), gatedFlatnessSum/durationFrames);

		/// TODO: could be handy to have running maxima also

	}).send(s);
	s.sync;


	b = Bus.audio(s, 1);
	~input = { Out.ar(b.index, SoundIn.ar(0)) }.play(s);

	~capture_buf_frames = s.sampleRate * 32.0;
	~capture_buf = Buffer.alloc(s, ~capture_buf_frames);

	z = Synth.new(\amp_ons, [\in, b.index]);
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
	~data_frame = Array.newClear(6);

	~handle_onset_trigger = {
		arg time, id, value;
		~data_frame[id] = value;
		// bad hack: assuming magic number of ids, assuming last id arrives last
		if (id == 5, {
			if (~data_frame.indexOf(nil).notNil, {
				postln("whoops, incomplete data frame");
			}, {
				// TODO: would be smart to check here that timestamps are reasonable
				~handle_data.value(time, Dictionary.newFrom([
					\position, ~data_frame[0],
					\duration, ~data_frame[1],
					\amplitude, ~data_frame[2],
					\percentile, ~data_frame[3],
					\centroid, ~data_frame[4],
					\flatness, ~data_frame[5],
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
		if(~last_data.notNil, {
			// save the previous data so next onset doesn't stomp it
			var data0 = ~last_data;
			Routine {
				var tmp, dur, pos, pos0, durFrames, outputPath;
				dur = data[\duration];
				durFrames = dur * s.sampleRate;
				if (dur > ~capture_buf.duration, {
					postln("time since last onset exceeds buffer duration; skipping output");
				}, {
					tmp = Buffer.alloc(s, durFrames);
					s.sync;

					/// FIXME: could add some pre/postroll here.
					/// (one possible motivator is that the position reporting will have some jitter,
					/// due to AR/KR and analysis latency)
					pos = data[\position];
					pos0 = data0[\position];
					if (pos > pos0, {
						~capture_buf.copyData(tmp, srcStartAt:pos0, numSamples:durFrames);
					}, {
						// wrapped the buffer: perform 2x copy steps
						var n = ~capture_buf.size-pos0;
						~capture_buf.copyData(tmp, srcStartAt:pos0, numSamples:n);
						~capture_buf.copyData(tmp, dstStartAt:n, numSamples:(durFrames-n));
					});
					s.sync;

					outputPath = ~get_output_paths.value(time, data);
					tmp.write(outputPath[0], headerFormat:"wav");
					s.sync;
					tmp.free;

					/// TODO/FIXME: write data file
					/// it would really be nicer to have a single data output with column headers,
					/// and append each file's data to it
					// writeCsvIGuess(outputPath[0]);

				});
			}.play;
		});
		~last_data = data;
	};

	~get_output_paths = { arg time, data;
		var path = PathName(Document.current.path).pathOnly.asString;
		path = path ++ time.floor.asString;
		[
			path ++ ".wav",
			path ++ ".csv",
		]
	};

}.play;