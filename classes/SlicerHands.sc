// simple data visualizer for OnsetSlicer data
SlicerEyes {
	classvar <>histoBins, <>windowBounds, <>histoViewBounds, <>histoLabelBounds;

	// data
	var <sortedData;
	var <histograms;

	// gui
	var <histoWindow, <histoLayout;

	*initClass {
		histoBins= 32;
		histoViewBounds = 256@64;
		histoLabelBounds = 128@64;
		windowBounds = 600@600;
	}

	*new {
		arg onsetData, sliceBuffers;
		^super.new.init(onsetData, sliceBuffers);
	}

	init {
		arg onsetData, sliceBuffers;

		// fields of interest in OnsetSlicer data:
		var fields = [
			\duration,      // estimated initial non-silent duration, in seconds
			\audibleTime,   // total non-silent time in segment
			\amplitudeAvg,  // amplitude averaged over non-silent initial duration
			\amplitudeMax,  // maximum amplitude in segment
			\percentileAvg, // upper-percentile break frequency, averaged over non-silent control frames
			\centroidAvg,   // spectral magnitude centroid frequency, averaged over non-silent control frames
			\flatnessAvg,   // spectral flatness, averaged over non-silent control frames
			\flatnessMin,   // minimum spectral flatness in segment
		];

		var histoViewRows = List.new;

		sortedData = Dictionary.new;
		histograms = Dictionary.new;
		histoWindow = Window("slicer eyes", windowBounds);
		histoLayout = histoWindow.addFlowLayout(10@10, 10@2);

		fields.do({ arg key;
			var data, histo, str, maxCount;

			data = onsetData.keys.collect({ arg stamp;
				var dataFrame = onsetData[stamp];
				dataFrame[key]
			}).asArray.sort;

			key.postln;
			data.postln;
			sortedData[key] = data;
			histo = data.histo(histoBins);
			histograms[key] = histo;
			maxCount = histo.maxItem;
			str = key.asString ++ "\nmin:" ++ data.minItem.round(0.001) ++ "\nmax:" ++ data.maxItem.round(0.001);
			StaticText(histoWindow.view, histoLabelBounds).string_(str);
			MultiSliderView(histoWindow.view, histoViewBounds)
			.elasticMode_(true)
			.isFilled_(true)
			.drawRects_(true)
			.value_(histo/maxCount);
			histoLayout.nextLine;
		});

		histoWindow.front;
	}

}

/// basic sampler consuming OnsetSlicer session data
SlicerHands {

	classvar def;
	classvar defaultMapFunction;

	var map;

	*initClass {

		// mapping function should take a dataset
		defaultMapFunction = {
			arg data, noteList;

			if (noteList.isNil, {
				noteList = Array.series(128);
			});

			// TODO...
		}
	}

	*new { arg onsetData, sliceBuffers, server, outputBus, target;
		^super.new.init(onsetData, sliceBuffers, server, outputBus, target);
	}

	init {
		arg aOnsetData, aSliceBuffers, aServer, aOutputBus, aTarget;

		var server = if(aServer.isNil, { Server.default }, { aServer });
		var outputBus = if(aOutputBus.isNil, { 0 }, { aOutputBus });
		var target = if(aTarget.isNil, { server }, { aTarget });

		SynthDef.new(\SlicerHands_SimplePlayer, {
			arg buf, out=0, amp=1, dur, atk=0.005, rel=0.1, gate=1;
			var aenv;
			var aenvStop;
			aenv = EnvGen.ar(Env.new([0, 1, 1, 0], [atk, dur-(atk+rel), rel], 'welch'));
			aenvStop = EnvGen.ar(Env.asr(0, 1, 0.01), gate, doneAction:2);
			Out.ar(out, PlayBuf.ar(1, buf) * aenv * aenvStop);
		}).send(server);

		server.sync;

		/// TODO....
	}


	noteOn { arg num, vel;
		// TODO...
	}

	noteOff { arg num, vel;
		// TODO....
	}



}