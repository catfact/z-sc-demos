s = Server.default;

// change device names here, or comment out to use the system defaults
s.options.inDevice_("Steinberg UR-RT2");
s.options.outDevice_("Steinberg UR-RT2");

s.waitForBoot {
	Routine {
		~input_bus = Bus.audio(s, 1);
		~input_synth = { Out.ar(~input_bus.index, SoundIn.ar(0)) }.play(s);
		{ ~input_bus.scope }.defer;

		OnsetSlicer.sendSynthDef(s);
		postln("onset analysis parameters: ");
		OnsetSlicer.def.allControlNames.do({ arg ctl; ctl.name.postln; });
		s.sync;

		o = OnsetSlicer.new(s, inputBus:~input_bus.index, target:~input_synth, addAction:\addAfter);
		s.sync;
		o.synth.set(\threshold, 0.1);

		o.shouldKeepSessionBuffers_(true);
		o.shouldKeepSessionData_(true);

		// stop after 10 seconds of total buffers are captured
		o.sessionMaxTotalBufferSamples_(s.sampleRate * 10);

		o.dataFrameCallback_({ arg data;i
			// "\ndata frame callback: ".postln;
			// data.keys.do({ arg k; [k, data[k]].postln; });
		});
		o.segmentDoneCallback_({ arg timeStamp, data;
			// ["segment done callback: ", timeStamp, data].postln;
		});
		o.sessionDoneCallback = ~session_done;

		s.sync;
		postln("starting session...");

		o.startSession;

		// end the session manually or when buffer limit is reached
		/*
		16.wait;

		postln("ending session...");
		o.endSession;
		*/

	}.play;
};

// this stuff will run when the session completes
~session_done =	{
	{ e = SlicerEyes.new(o.sessionData, o.sessionBuffers); }.defer;

	~out_bus= Bus.audio(s, 1);
	{ ~out_bus.scope }.defer;
	~master_out = {Out.ar(0, In.ar(~out_bus.index, 1).dup)}.play(s);
	~sampler_group = Group.before(~master_out);

	Routine {
		// arbitrary note numbers for my particular pad controller configuration:
		var noteList = [60, 62, 64, 65, 67, 69];
		h = SlicerHands.new(o.sessionData, o.sessionBuffers, noteList, target:~sampler_group);
		h.outBus = ~out_bus.index;

		MIDIIn.connectAll;
		~note_on = MIDIFunc.noteOn({ arg vel, num, chan;
			postln("noteon: [num, vel, chan] = " ++ [num, vel, chan]);
			h.noteOn(num, vel);
		});
	}.play;
};