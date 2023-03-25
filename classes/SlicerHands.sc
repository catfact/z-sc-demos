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
			str = key.asString
			++ "\nmin: " ++ data.minItem.round(0.001)
			++ "\nmax: " ++ data.maxItem.round(0.001);
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
	classvar <>defaultMapFunction;

	var <>mapFunction, <>bufGains;
	var <data, <buffers, <map;
	var <server, <>outBus, <target;

	// we'll give each note a `Group` so that velocity layers can stop eachother
	var <noteGroupNodes;

	// behavior flag: if set, each note stops other synths triggered by same note
	var <>shouldSoloNoteGroups;

	*initClass {
		// mapping function should take a dataset and an optional list of notes
		// produces a dictionary where:
		// - keys are note numbers
		// - each element is a list of Buffers, arranged by velocity
		defaultMapFunction = {
			arg data, noteList;
			var numNotes=noteList.size;
			var numSamples = data.size;
			var idSorted;
			var div, remain, idx;
			var sizeList, grouped;
			var result = Dictionary.new;

			// sort the samples by primary parameter
			// using `minFlatness` will sort more-tonal samples towards the bottom
			idSorted = data.keys.asArray.sort({ arg a, b;
				data[a][\flatnessMin] > data[b][\flatnessMin]
			});

			idSorted.do({ arg id; data[id][\flatnessMin].postln; });

			// we want to clump the sorted list into <num notes> groups,
			// then sort each group by the secondary parameter
			div = (numSamples / numNotes).asInteger;
			remain = numSamples - (numNotes * div);
			postln("[div, remain] = " ++ [div, remain]);

			// i'd like to deal with the remainder by assigning more samples to lower notes
			// one way to deal with this is with the `clumps` method,
			// which acepts a list of group sizes
			sizeList = Array.fill(numNotes, div);
			idx = 0;
			remain.do({
				sizeList[idx] = sizeList[idx] + 1;
				idx = idx + 1;
			});
			grouped = idSorted.clumps(sizeList);

			// sort each subgroup by the secondary parameter
			// in this case we'll use `centroidAvg`,
			// attempting to sort brighter/higher sounds towards the top
			noteList.do({ arg num, i;
				var group, sorted;
				postln("building layers for [note, index] = " ++ [num, i]);
				group = grouped[i];
				sorted = group.sort({arg a, b;
					data[a][\centroidAvg] < data[b][\centroidAvg]
				});
				result[num] = sorted;
			});
			result
		};
	}

	*new {
		arg onsetData, sliceBuffers,
		noteList=nil, mapFunction=nil,
		server=nil, outBus=nil, target=nil;

		^super.new.init(onsetData, sliceBuffers,
			noteList, mapFunction,
			server, outBus, target);
	}

	init {
		arg aOnsetData, aSliceBuffers, aNoteList, aServer, aOutBus, aTarget;

		var noteList = if (aNoteList.isNil, { Array.series(128) }, {aNoteList});

		// target amplitude for normalization
		var normAmpTarget = -12.dbamp;

		shouldSoloNoteGroups = true;

		data = aOnsetData;
		buffers = aSliceBuffers;

		server = if(aServer.isNil, { Server.default }, { aServer });
		outBus = if(aOutBus.isNil, { 0 }, { aOutBus });
		target = if(aTarget.isNil, { server }, { aTarget });

		/// FIXME: don't really need to compile the def for each instance, but not a big deal
		SynthDef.new(\SlicerHands_OneShotPlayer, {
			arg buf, out=0, amp=1, dur=1, atk=0.005, rel=0.1, gate=1;
			var aenv;
			var aenvStop;
			aenv = EnvGen.ar(Env.new([0, 1, 1, 0], [atk, dur-(atk+rel), rel], 'welch'));
			aenvStop = EnvGen.ar(Env.asr(0, 1, 0.01), gate, doneAction:2);
			Out.ar(out, PlayBuf.ar(1, buf, doneAction:2) * aenv * aenvStop);
		}).send(server);

		server.sync;

		// generate a list of gain values per buffer (for non-destructive normalization)
		bufGains = Dictionary.new;
		buffers.keys.do({ arg key;
			bufGains[key] = normAmpTarget / data[key][\amplitudeMax];
		});

		mapFunction = defaultMapFunction;
		map = mapFunction.value(data, noteList);

		noteGroupNodes = Dictionary.new;
		noteList.do({ arg num; noteGroupNodes[num] = Group.new(target) });

	}


	noteOn { arg num, vel;
		var groupNode = noteGroupNodes[num];
		var layerList = map[num];
		if (groupNode.isNil, {
			postln("no group node found for note number " ++ num);
		}, {
			if (layerList.isNil, {
				postln("no layer list found for note number " ++ num);
			}, {
				var layerIdx = vel.linlin(0, 127, 0, layerList.size-1).asInteger;
				var id = layerList[layerIdx];
				var buf = buffers[id];
				var gain = bufGains[id];
				var dur = data[id][\duration];
				postln("playing; [layer, id, buf, gain, dur] = " ++ [layerIdx, id, buf, gain, dur]);

				if (shouldSoloNoteGroups, {
					groupNode.set(\gate, 0);
				});

				Synth.new(\SlicerHands_OneShotPlayer, [
					\out, outBus, \buf, buf.bufnum, \amp, gain, \dur, dur
				], groupNode);
			});
		});
	}

	noteOff { arg num, vel;
		// basically thinking of this as a percussive instrument,
		// so nothing to do i guess
	}



}