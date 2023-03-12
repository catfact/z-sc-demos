
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
		SendTrig.kr(onsets, \idAmpAvg.ir(2), ampSum/duration);
		SendTrig.kr(onsets, \idPcileAvg.ir(3), pcileSum/duration);
		SendTrig.kr(onsets, \idCentroidAvg.ir(4), centroidSum/duration);
		SendTrig.kr(onsets, \idGatedFlatnessAvg.ir(5), gatedFlatnessSum/duration);

		/// TODO: could be handy to have running maxima also

	}).send(s);
	s.sync;


	b = Bus.audio(s, 1);
	~input = { Out.ar(b.index, SoundIn.ar(0)) }.play(s);

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
		if (node == z.nodeId, {
			~handle_onset_trigger(time, id, value);
		});
	},'/tr', s.addr);


	// kinda hacky, would be nice if `SendTrig` could send an array (can it?)
	~packet = Array.newClear(6);
	~handle_trig = {
		arg time, id, value;
		~packet[id] = value;
		// bad hack: assuming magic number of ids, assuming last id arrives last
		if (id == 5, {
			if (~packet.indexOf(nil).notNil, {
				postln("whoops, incomplete packet");
			}, {
				// TODO: would be smart to check here that timestamps are reasonable

			});
		});

	};

}.play;