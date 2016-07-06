define(['jquery',
		'd3js',
		'../common/util/templatesUtil',
		'../common/constants'],
	function ($,
	          d3,
	          templatesUtil,
	          constants) {

		const plainId = templatesUtil.composeId;
		const jqId = templatesUtil.composeJqId;
		const svgBlockId = plainId([templatesUtil.testsTabTypes().CHARTS, 'block']);
		const CHART_TYPE = templatesUtil.chartTypes();

		var currentChartBoards;
		var currentMetric;
		var currentTimeUnit;
		var updateFlag = true;

		function getSvgId(chartBoardName) {
			return plainId(['chartboard', chartBoardName]);
		}

		function getSvgSelector(chartBoardName) {
			return jqId([getSvgId(chartBoardName)]);
		}

		function doesSvgExist(chartBoardName) {
			return $(getSvgSelector(chartBoardName)).length
		}

		const SCALE = {
			LINEAR: 'linear',
			LOG: 'log',
			fullName: function (scaleType, scaleName) {
				return scaleType + ' ' + scaleName + ' scale';
			}
		};

		const SCALE_SWITCH = [
			{
				name: 'x',
				shift: 0
			},
			{
				name: 'y',
				shift: 30
			}
		];

		//  for time accomodation. For more info see #JIRA-314
		const TIME_UNIT = {
			seconds: {
				limit: 300,
				value: 1,
				next: 'minutes',
				label: 't[s]'
			},
			minutes: {
				limit: 300,
				value: 60,
				next: 'hours',
				label: 't[m]'
			},
			hours: {
				limit: 120,
				value: 60 * 60,
				next: 'days',
				label: 't[h]'
			},
			days: {
				limit: 35,
				value: 24 * 60 * 60,
				next: 'weeks',
				label: "t[D]"
			},
			weeks: {
				limit: 20,
				value: 7 * 24 * 60 * 60,
				next: 'months',
				label: 't[W]'
			},
			months: {
				limit: 60,
				value: 4 * 7 * 24 * 60 * 60,
				next: 'years',
				label: 't[M]'
			},
			years: {
				next: null,
				label: 't[Y]'
			},
			toMinutes: function (x) {
				return x * this.minutes.value;
			},
			toHours: function (x) {
				return x * this.hours.value;
			},
			toDays: function (x) {
				return x * this.days.value;
			},
			toWeeks: function (x) {
				return x * this.weeks.value;
			},
			toMonths: function (x) {
				return x * this.months.value;
			},
			toYears: function (x) {
				return x * this.years.value;
			},
			toUnits: function (x, unit) { 
				if (unit) {
					return x / unit.value;
				} else {
					return x;
				}
			}
		};

		function tuneUnits(currentTimeValue) {
			if (currentTimeValue > currentTimeUnit.limit) {
				currentTimeUnit = TIME_UNIT[currentTimeUnit.next];
			}
		}

		const MARGIN = {
			TOP: 20,
			RIGHT: 20,
			BOTTOM: 180,
			LEFT: 100
		};
		const WIDTH = 1200 - MARGIN.LEFT - MARGIN.RIGHT;
		const HEIGHT = 600 - MARGIN.TOP - MARGIN.BOTTOM;

		const defaultsFactory = function () {

			function createDefaultTimeScale() {
				return d3.time.scale();
			}

			function createDefaultLinearScale() {
				return d3.scale.linear();
			}

			function createDefaultLogScale() {
				return d3.scale.log();
			}

			function createDefaultAxis() {
				return d3.svg.axis();
			}

			function createDefaultLineGenerator() {
				return d3.svg.line();
			}

			function createDefaultColorizer() {
				return d3.scale.category10();
			}

			return {
				timeScale: createDefaultTimeScale,
				linearScale: createDefaultLinearScale,
				logScale: createDefaultLogScale,
				axis: createDefaultAxis,
				lineGenerator: createDefaultLineGenerator,
				colorizer: createDefaultColorizer
			}
		}();

		const AXIS_X_WIDTH = Math.round(WIDTH / 1.5);
		const AXIS_Y_WIDTH = HEIGHT;

		var minXAccessorValue;
		var minYAccessorValue;
		setMinXDefaultAccessorValue();
		setMinYDefaultAccessorValue();

		function setMinXLogAccessorValue() {
			minXAccessorValue = 0.1 / currentTimeUnit.value;
		}

		function setMinYLogAccessorValue() {
			minYAccessorValue = 0.001;
		}

		function setMinXDefaultAccessorValue() {
			minXAccessorValue = 0;
		}

		function setMinYDefaultAccessorValue() {
			minYAccessorValue = 0;
		}

		function xAccessor(data) {
			const convertedX = TIME_UNIT.toUnits(data.x, currentTimeUnit);
			return convertedX <= 0 ? minXAccessorValue : convertedX;
		}

		function yAccessor(data) {
			return data.y <= 0 ? minYAccessorValue : data.y;
		}

		const linearScale1 = defaultsFactory.linearScale();
		const linearScale2 = defaultsFactory.linearScale();
		const logScale1 = defaultsFactory.logScale();
		const logScale2 = defaultsFactory.logScale();
		const axis1 = defaultsFactory.axis();
		const axis2 = defaultsFactory.axis();
		const lineGenerator = defaultsFactory.lineGenerator();
		const colorizer = defaultsFactory.colorizer();
		var xScale;
		var yScale;
		var xAxis;
		var yAxis;
		var line;

		function setLinearXScale() {
			xScale = linearScale1.range([0, AXIS_X_WIDTH]);
		}

		function setLinearYScale() {
			yScale = linearScale2.range([AXIS_Y_WIDTH, 0]);
		}

		function setLogXScale() {
			xScale = logScale1.range([0, AXIS_X_WIDTH]);
		}

		function setLogYScale() {
			yScale = logScale2.range([AXIS_Y_WIDTH, 0]);
		}

		function updateDefaultAxisX() {
			xAxis = axis1.scale(xScale).orient('bottom').ticks(5).innerTickSize(-AXIS_Y_WIDTH).outerTickSize(0).tickPadding(10);
		}

		function updateDefaultAxisY() {
			yAxis = axis2.scale(yScale).orient('left').ticks(5).innerTickSize(-AXIS_X_WIDTH).outerTickSize(0).tickPadding(10);
		}

		function updateLogAxisX() {
			xAxis = axis1.scale(xScale).orient('bottom').ticks(5, d3.format(",d")).innerTickSize(-AXIS_Y_WIDTH).outerTickSize(0).tickPadding(10);
		}

		function updateLogAxisY() {
			yAxis = axis2.scale(yScale).orient('left').ticks(5, d3.format(",d")).innerTickSize(-AXIS_X_WIDTH).outerTickSize(0).tickPadding(10);
		}

		function updateLine() {
			line = lineGenerator.x(scaledXAccessor).y(scaledYAccessor);
		}

		function switchScaling(scale, axis, svgElem) {
			switch (scale) {
				case SCALE.LINEAR:
					switch (axis) {
						case 'x':
							MIN_THREADS_COUNT = 0;
							svgElem.attr('xaxis', SCALE.LINEAR);
							setMinXDefaultAccessorValue();
							setLinearXScale();
							updateDefaultAxisX();
							break;
						case 'y':
							svgElem.attr('yaxis', SCALE.LINEAR);
							setMinYDefaultAccessorValue();
							setLinearYScale();
							updateDefaultAxisY();
							break;
					}
					break;
				case SCALE.LOG:
					switch (axis) {
						case 'x':
							svgElem.attr('xaxis', SCALE.LOG);
							MIN_THREADS_COUNT = 1;
							setMinXLogAccessorValue();
							setLogXScale();
							updateLogAxisX();
							break;
						case 'y':
							svgElem.attr('yaxis', SCALE.LOG);
							setMinYLogAccessorValue();
							setLogYScale();
							updateLogAxisY();
							break;
					}
					break;
				default:
					setMinXDefaultAccessorValue();
					setMinYDefaultAccessorValue();
					setLinearXScale();
					setLinearYScale();
					updateDefaultAxisX();
					updateDefaultAxisY();
			}
			updateLine();
		}

		switchScaling();

		function scaledXAccessor(data) {
			return xScale(xAccessor(data));
		}

		function scaledYAccessor(data) {
			return yScale(yAccessor(data));
		}

		function extent(array, accessor) {
			return d3.extent(array.values, accessor);
		}

		function deepExtent(array, accessor) {
			return [
				d3.min(array, function (anArray) {
					return d3.min(anArray.values, accessor);
				}),
				d3.max(array, function (anArray) {
					return d3.max(anArray.values, accessor);
				})
			]
		}

		function createSvg(parentId, svgId) {
			const chartsBlockId = jqId([parentId]);
			var svgChain = d3.select(chartsBlockId)
				.append('svg')
				.attr('id', svgId)
				.attr('width', WIDTH + MARGIN.LEFT + MARGIN.RIGHT)
				.attr('height', HEIGHT + MARGIN.TOP + MARGIN.BOTTOM);
			svgChain = svgChain.append('g')
				.attr('transform',
					'translate(' + (MARGIN.LEFT + 70) + ',' + (MARGIN.TOP + 70) + ')');
			const svgLinkElem = $('<a/>', {
				id: svgId + '-svg',
				class: 'chart-link',
				href: chartAsSvg(svgId),
				download: svgId + '.svg'
			});
			const pngLinkElem = $('<a/>', {
				id: svgId + '-png',
				class: 'chart-link',
				href: chartAsPng(svgId),
				download: svgId + '.png'
			});
			svgLinkElem.text('svg');
			pngLinkElem.text('png');
			$(chartsBlockId).append(svgLinkElem);
			$(chartsBlockId).append(pngLinkElem);
			return svgChain;
		}

		function svgXml(svgId) {
			const svg = document.getElementById(svgId);
			const serializer = new XMLSerializer();
			var source = serializer.serializeToString(svg);

			if (!source.match(/^<svg[^>]+"http\:\/\/www\.w3\.org\/1999\/xlink"/)) {
				source = source.replace(/^<svg/, '<svg xmlns:xlink="http://www.w3.org/1999/xlink"');
			}
			return '<?xml version="1.0" standalone="no"?>\r\n' + source;
		}

		function chartAsSvg(svgId) {
			return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svgXml(svgId));
		}

		function chartAsPng(svgId) {
			const image = new Image();
			image.src = 'data:image/svg+xml;base64,' + window.btoa(unescape(encodeURIComponent(svgXml(svgId))));
			const canvas = document.createElement('canvas');
			canvas.width = image.width;
			canvas.height = image.height;
			const context = canvas.getContext('2d');
			context.drawImage(image, 0, 0);
			return canvas.toDataURL('image/png');
		}

		function createAxes(svgElement) {
			svgElement.append('g')
				.attr('class', 'x-axis axis')
				.attr('transform', 'translate(0, ' + HEIGHT + ')')
				.call(xAxis);

			svgElement.append('text')
				.attr('class', 'x-axis-text axis-text')
				.attr('x', AXIS_X_WIDTH / 2)
				.attr('y', HEIGHT + MARGIN.BOTTOM / 3)
				.style('text-anchor', 'middle')
				.text(currentTimeUnit.label);

			svgElement.append('g')
				.attr('class', 'y-axis axis')
				.call(yAxis);

			svgElement.append('text')
				.attr('class', 'y-axis-text axis-text')
				.attr('transform', 'rotate(-90)')
				.attr('y', 0 - Math.round(MARGIN.LEFT * 1.5))
				.attr('x', 0 - (AXIS_Y_WIDTH / 2))
				.attr('dy', '1em')
				.style('text-anchor', 'middle');
		}

		function createLabel(svgElement, text) {
			svgElement.append('text')
				.attr('x', (AXIS_X_WIDTH / 2))
				.attr('y', 0 - (MARGIN.TOP / 2))
				.attr('text-anchor', 'middle')
				.style('font-size', '16px')
				.style('text-decoration', 'underline')
				.text(text);
		}

		function createScaleSwitches(svgElement, chartBoardName) {
			const scaleSwitch = svgElement.selectAll('.scale-switch').data(SCALE_SWITCH);
			const scaleSwitchEnter = scaleSwitch.enter().append('g')
				.attr('class', plainId(['scale', 'switch']))
				.attr('id', function (scaleObj) {
					return plainId(['scale', 'switch', scaleObj.name, chartBoardName]);
				})
				.attr('scale', 'linear');
			scaleSwitchEnter.append('circle')
				.attr('cx', 15)
				.attr('cy', function (scaleObj) {
					return HEIGHT + (MARGIN.BOTTOM / 3) + scaleObj.shift;
				})
				.attr('r', 7)
				.style('stroke', 'black')
				.style('stroke-width', 1)
				.style('fill', '#ECE9E9')
				.on('click', function (scaleObj) {
					const svgElem = d3.select(getSvgSelector(chartBoardName));
					const switchElem = d3.select(jqId(['scale', 'switch', scaleObj.name, chartBoardName]));
					const switchCircle = switchElem.select('circle');
					const switchText = switchElem.select('text');
					if (switchElem.attr('scale') == SCALE.LINEAR) {
						switchCircle.style('fill', 'black');
						switchText.text(function (scaleObj) {
							switchScaling(SCALE.LOG, scaleObj.name, svgElem);
							return SCALE.fullName(SCALE.LOG, scaleObj.name);
						});
						switchElem.attr('scale', SCALE.LOG);
						const chartBoardName = svgElement.attr('name');
						const chartBoardContent = currentChartBoards[chartBoardName];
						updateChartBoard(chartBoardName, chartBoardContent, currentMetric);
					} else {
						switchCircle.style('fill', '#ECE9E9');
						switchText.text(function (scaleObj) {
							switchScaling(SCALE.LINEAR, scaleObj.name, svgElem);
							return SCALE.fullName(SCALE.LINEAR, scaleObj.name)
						});
						switchElem.attr('scale', SCALE.LINEAR);
						const chartBoardName = svgElement.attr('name');
						const chartBoardContent = currentChartBoards[chartBoardName];
						updateChartBoard(chartBoardName, chartBoardContent, currentMetric);
					}
				});
			scaleSwitchEnter.append('text')
				.attr('x', 30)
				.attr('y', function (scaleObj) {
					return HEIGHT + (MARGIN.BOTTOM / 3) + scaleObj.shift;
				})
				.text(function (scaleObj) {
					return SCALE.fullName(SCALE.LINEAR, scaleObj.name)
				});
		}

		function handleDataObj(dataObj) {
			var values = dataObj.values;
			if (values.length > 0) {
				if (values[values.length - 1] === null) {
					values.pop();
				}
				values.forEach(function (point) {
					if (point !== null) {
						point.x = +point.x;
						point.y = +point.y;
					}
				})
			}
		}

		const simpleChartPattern = new RegExp('^[0-9]+-.+');

		function processChartBoards(chartBoards, metric, chartType, notOverrideChartBoards, notOverrideMetric) {
			if (!notOverrideChartBoards) {
				currentChartBoards = chartBoards;
			}
			if (!notOverrideMetric) {
				currentMetric = metric;
			}
			const simpleCharts = {};
			const forEachCharts = {};
			if (currentChartBoards) {
				$.each(currentChartBoards, function (chartBoardName) {
					if (simpleChartPattern.test(chartBoardName)) {
						simpleCharts[chartBoardName] = currentChartBoards[chartBoardName];
					} else {
						forEachCharts[chartBoardName] = currentChartBoards[chartBoardName];
					}
				});
				var tempChartBoards;
				if (chartType === CHART_TYPE.CURRENT) {
					tempChartBoards = simpleCharts;
				} else {
					tempChartBoards = forEachCharts;
				}
				$.each(tempChartBoards, function (chartBoardName, chartBoardContent) {
					if (!doesSvgExist(chartBoardName)) {
						currentTimeUnit = TIME_UNIT.seconds;
						createChartBoard(chartBoardName);
					}
					updateChartBoard(chartBoardName, chartBoardContent, currentMetric);
					const svgId = getSvgId(chartBoardName);
					$(jqId([svgId, 'svg'])).attr('href', chartAsSvg(svgId));
					$(jqId([svgId, 'png'])).attr('href', chartAsPng(svgId));
				});
			}
		}

		function createChartBoard(chartBoardName) {
			const svgCanvasChain = createSvg(svgBlockId, getSvgId(chartBoardName));
			svgCanvasChain.attr('name', chartBoardName);
			const svg = d3.select(getSvgSelector(chartBoardName));
			svg.attr('xaxis', SCALE.LINEAR);
			svg.attr('yaxis', SCALE.LINEAR);
			createLabel(svgCanvasChain, chartBoardName);
			createAxes(svgCanvasChain);
			createScaleSwitches(svgCanvasChain, chartBoardName);
		}

		function updateAxesLabels(svgElement, metricName, altMetricName) {
			svgElement.select('.x-axis-text')
				.duration(750)
				.text(function () {
					if (altMetricName === undefined) {
						return currentTimeUnit.label;
					} else {
						return altMetricName;
					}
				}());
			svgElement.select('.y-axis-text')
				.duration(750)
				.text(constants.CHART_METRICS_UNITS_FORMATTER[metricName]);
		}

		function updateAxes(svgElement, chartArr, minThreadsCount, isDomainScaled) {
			const xDomain = xScale.domain();
			tuneUnits(xDomain[xDomain.length - 1]);
			var tempDomain;
			tempDomain = extent($.extend(true, {}, chartArr[0]), xAccessor);
			if (minThreadsCount !== undefined && minThreadsCount < tempDomain[0]) {
				tempDomain[0] = minThreadsCount;
			}
			xScale.domain(tempDomain);
			tempDomain = deepExtent(chartArr, yAccessor);
			if (!isDomainScaled) {
				tempDomain[0] = minYAccessorValue;
			}
			yScale.domain(tempDomain).nice();
			svgElement.select('.x-axis')
				.call(xAxis);
			svgElement.select('.y-axis')
				.call(yAxis);
		}

		function updateCharts(svgCanvasElement, chartArr, chartBoardName, metricName, pointsEnable) {
			const chartContainer = svgCanvasElement.selectAll('.chart').data(chartArr);
			chartContainer.enter().append('g')
				.attr('class', 'chart')
				.attr('id', function (chart) {
					return plainId(['id', chartBoardName, metricName, chart.name, 'line']);
				});
			chartContainer.selectAll('path')
				.data(function (chart) {
					return [chart];
				})
				.enter().append('path')
				.attr('class', 'line')
				.attr('d', function (chart) {
					return line(chart.values)
				})
				.attr('fill', 'none')
				.style('stroke', function (chart) {
					return colorizer(chart.name);
				})
				.style('stroke-width', 1);
			var points;
			if (pointsEnable) {
				points = chartContainer.selectAll('circle')
					.data(function (chart) {
						$.each(chart.values, function (index, value) {
							value['color'] = colorizer(chart.name);
						});
						return chart.values;
					});
				points.enter().append('circle')
					.attr('cx', function (value) {
						return scaledXAccessor(value);
					})
					.attr('cy', function (value) {
						return scaledYAccessor(value);
					})
					.attr('r', 3)
					.style('fill', function (value) {
						return value.color;
					});
			}
			svgCanvasElement.selectAll('.axis path, .axis line')
				.style('fill', 'none')
				.style('stroke', 'grey')
				.style('stroke-width', '1')
				.style('shape-rendering', 'crispEdges');
			svgCanvasElement.selectAll('.tick line')
				.style('opacity', '0.2');
			chartContainer.exit().remove();
			const chartUpdate = chartContainer.transition();
			chartUpdate.select('path')
				.duration(750)
				.attr('d', function (chart) {
					return line(chart.values)
				});
			if (pointsEnable) {
				points.transition()
					.duration(750)
					.attr('cx', function (value) {
						return scaledXAccessor(value);
					})
					.attr('cy', function (value) {
						return scaledYAccessor(value);
					})
			}
		}

		function updateLegend(svgCanvasElement, chartArr, chartBoardName, metricName) {
			const legend = svgCanvasElement.selectAll('.legend').data(chartArr);
			const legendEnter = legend.enter().append('g')
				.attr('class', 'legend')
				.attr('id', function (chart) {
					return plainId(['id', chartBoardName, metricName, chart.name]);
				})
				.on('click', function () {
					const elemented =
						document.getElementById(plainId([this.id, 'line']));
					if ($(this).css('opacity') == 1) {
						d3.select(elemented)
							.transition()
							.duration(100)
							.style('opacity', 0);
						// .style('display', 'none');
						d3.select(this)
							.transition()
							.duration(100)
							.style('opacity', .2);
					} else {
						d3.select(elemented)
							.style('display', 'block')
							.transition()
							.duration(100)
							.style('opacity', 1);
						d3.select(this)
							.transition()
							.duration(100)
							.style('opacity', 1);
					}
				});
			const legendShift = [0, 30, 60];
			legendEnter.append('circle')
				.attr('cx', AXIS_X_WIDTH + 30)
				.attr('cy', function (chart, index) {
					return legendShift[index];
				})
				.attr('r', 7)
				.style('fill', function (chart) {
					return colorizer(chart.name);
				});
			legendEnter.append('text')
				.attr('x', AXIS_X_WIDTH + 45)
				.attr('y', function (chart, index) {
					return legendShift[index];
				})
				.text(function (chart) {
					return chart.name;
				});
			legend.exit().remove();
			const legendUpdate = legend.transition();
			legendUpdate
				.style('opacity', function () {
					const elemented =
						document.getElementById(plainId([this.id, 'line']));
					d3.select(elemented)
						.style('display', 'block')
						.style('opacity', 1);
					return 1;
				});
			legendUpdate.select('text')
				.text(function (chart) {
					return chart.name;
				});
		}

		var MIN_THREADS_COUNT = 0;

		function updateChartBoard(chartBoardName, chartBoardContent, metric) {
			updateFlag = false;
			if (!chartBoardName || !chartBoardContent) {
				return;
			}
			const svgSelector = getSvgSelector(chartBoardName);
			const svg = d3.select(svgSelector).transition();
			const chartArr = chartBoardContent[metric];
			const names = [];
			chartArr.forEach(function (chart) {
				handleDataObj(chart);
				names.push(chart.name);
			});
			colorizer.domain(names);
			const isDomainScaled = d3.select(getSvgSelector(chartBoardName)).attr('yaxis') === SCALE.LOG;
			const isSimpleChart = simpleChartPattern.test(chartBoardName);
			if (isSimpleChart) {
				updateAxes(svg, chartArr, undefined, isDomainScaled);
				updateAxesLabels(svg, metric);
			} else {
				updateAxes(svg, chartArr, MIN_THREADS_COUNT, isDomainScaled);
				updateAxesLabels(svg, metric, 'threads');
			}
			const svgCanvas = d3.select(svgSelector + ' g');
			if (isSimpleChart) {
				updateCharts(svgCanvas, chartArr, chartBoardName, metric, false);
			} else {
				updateCharts(svgCanvas, chartArr, chartBoardName, metric, true);

			}
			updateLegend(svgCanvas, chartArr, chartBoardName, metric);
			updateFlag = true;
		}

		return {
			processCharts: processChartBoards
		};
	});