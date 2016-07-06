/**
 * Created on 08.04.16.
 */
define(['jquery'], function ($) {

	const PROPERTIES = {
		DISABLED: 'disabled'
	};

	// todo remove the duplication
	// function executeForAll(jQueryFunction, args) {
	// 	$.each(args, function (index, value) {
	// 		jQueryFunction.call($(value));
	// 	});
	// }

	function disable(jQuerySelector) {
		$.each(arguments, function (index, value) {
			$(value).prop(PROPERTIES.DISABLED, true);
		});
	}

	function enable(jQuerySelector) {
		$.each(arguments, function (index, value) {
			$(value).prop(PROPERTIES.DISABLED, false);
		});
	}

	function show(jQuerySelector) {
		$.each(arguments, function (index, value) {
			$(value).show();
		});
	}

	function hide(jQuerySelector) {
		$.each(arguments, function (index, value) {
			$(value).hide();
		});
	}

	function emptyValue(jQuerySelector) {
		$.each(arguments, function (index, value) {
			$(value).val('');
		});
	}

	function addClass(className, jQuerySelector) {
		for (var i = 1; i < arguments.length; i++) {
			$(arguments[i]).addClass(arguments[0]);
		}
	}

	function removeClass(className, jQuerySelector) {
		for (var i = 1; i < arguments.length; i++) {
			$(arguments[i]).removeClass(arguments[0]);
		}
	}

	function addIdByElem(id, jQueryElement) {
		jQueryElement.attr('id', id);
	}
	
	function processClassElementsById(className, idDetector, trueEvent, falseEvent) {
		$('.' + className).each(function (index, elemSelectorObj) {
			if (elemSelectorObj.id && ((typeof elemSelectorObj.id) === 'string')) {
				if (elemSelectorObj.id.indexOf(idDetector) >= 0) {
					trueEvent($(elemSelectorObj));
				} else {
					falseEvent($(elemSelectorObj));
				}
			}
		})
	}

	function processClassElements(className, event) {
		$('.' + className).each(function (index, elemSelectorObj) {
				event($(elemSelectorObj));
		})
	}

	return {
		disable: disable,
		enable: enable,
		show: show,
		hide: hide,
		emptyValue: emptyValue,
		addClass: addClass,
		removeClass: removeClass,
		addIdByElem: addIdByElem,
		processClassElementsById: processClassElementsById,
		processClassElements: processClassElements
	}
});
