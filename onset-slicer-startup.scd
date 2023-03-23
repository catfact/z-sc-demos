s = Server.default;

// change device name here, or comment out to use the system default
s.options.inDevice_("Steinberg UR-RT2");

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
		o.dataFrameCallback_({ arg data;
			// "\ndata frame callback: ".postln;
			// data.keys.do({ arg k; [k, data[k]].postln; });
		});
		o.segmentDoneCallback_({ arg timeStamp, data;
			// ["segment done callback: ", timeStamp, data].postln;
		});

		s.sync;
		postln("starting session...");
		o.startSession;

		32.wait;

		postln("ending session...");
		o.endSession;

	}.play;
}