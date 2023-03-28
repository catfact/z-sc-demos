s = Server.default;

// change device names here, or comment out to use the system defaults
//s.options.inDevice_("Steinberg UR-RT2");
//s.options.outDevice_("Steinberg UR-RT2");

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

		o.dataFrameCallback_({ arg data;
			{ ~running_viz_win.refresh; }.defer;
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


// running visualizer
~running_viz_win = Window("slice data", 512@512);
~running_viz_win.view.background_(Color.black);
~running_viz_win.drawFunc = {
	if (o.notNil, {
		var data = o.sessionData;
		data.do({ arg frame;
			var b, pos, color, size,
			durNorm, ampNorm, centNorm, flatNorm;

			b = ~running_viz_win.bounds;

			/// highly arbitrary ranges here
			durNorm = frame[\duration].linlin(0, 4, 0, 1);
			ampNorm = frame[\amplitudeMax].ampdb.linlin(-30, 0, 0, 1);
			centNorm = frame[\centroidAvg].cpsmidi.linlin(30, 90, 0, 1);
			/// inverting flatness here
			flatNorm = frame[\flatnessMin].linlin(0, 0.7, 1, 0);

			[ frame[\flatnessMin], flatNorm].postln;

			pos = Point(centNorm * b.width, flatNorm * b.height);
			color = Color.grey(ampNorm.linlin(0, 1, 0.2, 1.0));
			size = durNorm.linlin(0, 1, 4, 20);

			Pen.fillColor = color;
			Pen.addWedge(pos, size, 0, 2*pi);
			Pen.fill;
		});
	});
};
~running_viz_win.front;