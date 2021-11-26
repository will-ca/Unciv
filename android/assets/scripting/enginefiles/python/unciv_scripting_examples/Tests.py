"""
Automated testing of practical Python scripting examples.
Intended to also catch breaking changes to the scripting API, IPC protocol, and reflective tools.


Call TestRunner.run_tests() to use.

Pass debugprint=False if running from Kotlin as part of build tests, because running scripts' STDOUT is already captured by the Python REPL and sent to Kotlin code, which should then check for the presence of the 'Exception' IPC packet flag.
"""


import os

import unciv, unciv_pyhelpers#, unciv_lib

from . import EndTimes, ExternalPipe, MapEditingMacros, Merfolk, PlayerMacros, ProceduralTechtree, Utils


# from unciv_scripting_examples.Tests import *; TestRunner.run_tests()

try:
	assert False
	# Can also check __debug__.
except:
	pass
else:
	raise RuntimeError("Assertions must be enabled to run Python tests.")


with open(Utils.exampleAssetPath("Elizabeth300"), 'r') as save:
	# TODO: Compress this.
	# Unciv uses Base64 and GZIP.
	Elizabeth300 = save.read()


def getTestGame():
	return unciv.GameSaver.gameInfoFromString(Elizabeth300)

def goToMainMenu():
	unciv.uncivGame.setScreen(unciv.apiHelpers.Factories.Gui.MainMenuScreen())


@Utils.singleton()
class InGame:
	"""Context manager object that loads a test save on entrance and returns to the main menu on exit."""
	def __enter__(self):
		unciv.uncivGame.loadGame(getTestGame())
	def __exit__(self, *exc):
		goToMainMenu()

@Utils.singleton()
class InMapEditor:
	"""Context manager object that loads a test map in the map editor on entrance and returns to the main menu on exit."""
	def __enter__(self):
		with Utils.TokensAsWrappers(getTestGame()) as (gameinfo,):
			unciv.uncivGame.setScreen(unciv.apiHelpers.Factories.Gui.MapEditorScreen(gameinfo.tileMap))
	def __exit__(self, *exc):
		goToMainMenu()


@Utils.singleton()
class TestRunner:
	"""Class for registering and running tests."""
	# No point using any third-party or Standard Library testing framework, IMO. The required behaviour's simple enough, and the output format to Kotlin ('Exception' flag or not) is direct enough that it's easier and more concise to just implement everything here.
	def __init__(self):
		self._tests = []
	class _TestCls:
		"""Class to define and run a single test. Accepts the function to test, a human-readable name for the test, a context manager with which to run it, and args and kwargs with which to call the funciton."""
		def __init__(self, func, name=None, runwith=None, args=(), kwargs={}):
			self.func = func
			self.name = getattr(func, '__name__', None) if name is None else name
			self.runwith = runwith
			self.args = args
			self.kwargs = kwargs
		def __call__(self):
			if self.runwith is None:
				self.func(*self.args, **self.kwargs)
			else:
				with self.runwith:
					self.func(*self.args, **self.kwargs)
	def Test(self, *args, **kwargs):
		"""Return a decorator that registers a function to be run as a test, and then returns it unchanged. Accepts the same configuration arguments as _TestCls."""
		# Return values aren't checked. A call that completes is considered a pass. A call that raises an exception is considered a fail.
		# If you need to check return values for a function, then just wrap them in another function with an assert.
		def _testdeco(func):
			self._tests.append(self._TestCls(func, *args, **kwargs))
			return func
		return _testdeco
	def run_tests(self, *, debugprint=True):
		"""Run all registered tests, printing out their results, and raising an exception if any of them fail."""
		failures = {}
		def _print(*args, **kwargs):
			print(*args, **kwargs)
			if debugprint:
				# When run as part of build, the Kotlin test-running code should be capturing the Python STDOUT anyway.
				unciv.apiHelpers.printLine(str(args[0]) if len(args) == 1 and not kwargs else " ".join(str(a) for a in args))
		for test in self._tests:
			try:
				test()
			except Exception as e:
				failures[test] = e
				n, t = '\n\t'
				_print(f"Python test FAILED: {test.name}\n\t{repr(e).replace(n, n+t)}")
			else:
				_print(f"Python test PASSED: {test.name}")
		_print("\n")
		if failures:
			exc = AssertionError(f"{len(failures)} Python tests FAILED: {[test.name for test in failures]}\n\n")
			_print(exc)
			raise exc
		else:
			_print(f"All {len(self._tests)} Python tests PASSED!\n\n")



#### Tests begin here. ####


@TestRunner.Test(runwith=InGame)
def NewGameTest():
	"""Example test. Explicitly tests that the InGame context manager is working."""
	# Everything below is the same, just explicitly passing existing functions to the registration function instead of using it as a decorator.
	assert unciv.apiHelpers.isInGame
	for v in (unciv.gameInfo, unciv.civInfo, unciv.worldScreen):
		assert unciv_pyhelpers.real(v) is not None
		assert unciv_pyhelpers.isForeignToken(v)


# Tests for PlayerMacros.py.

TestRunner.Test(runwith=InGame, args=(unciv.civInfo.cities, 0.5))(
	PlayerMacros.gatherBiggestCities
)
TestRunner.Test(runwith=InGame, args=(unciv.civInfo.cities,))(
	PlayerMacros.clearCitiesProduction
)
TestRunner.Test(runwith=InGame, args=(unciv.civInfo.cities, ("Scout", "Warrior", "Worker")))(
	PlayerMacros.addCitiesProduction
)
TestRunner.Test(runwith=InGame, args=(unciv.civInfo.cities,))(
	PlayerMacros.clearCitiesSpecialists
)
TestRunner.Test(runwith=InGame, args=(unciv.civInfo.cities,))(
	PlayerMacros.focusCitiesFood
)
TestRunner.Test(runwith=InGame, args=(unciv.civInfo.cities, ("Monument", "Shrine", "Worker")))(
	PlayerMacros.buildCitiesQueue
)
TestRunner.Test(runwith=InGame)(
	PlayerMacros.rebaseUnitsEvenly
)


# Tests for MapEditingMacros.py.

_m = MapEditingMacros

for _func in (
	_m.spreadResources,
	_m.dilateTileTypes,
	_m.erodeTileType,
	_m.floodFillSelected,
	_m.makeMandelbrot,
	_m.graph2D,
	_m.graph3D,
	_m.loadImageHeightmap,
	_m.loadImageColours
):
	for _cm in (InGame, InMapEditor):
		TestRunner.Test(runwith=_cm, name=f"{_func.__name__}-{_cm.__class__.__name__}")(_func)

del _m, _func, _cm


# Tests for ProceduralTechTree.py.

_m = ProceduralTechtree

for _func in (
	_m.extendTechTree,
	_m.clearTechTree,
	_m.scrambleTechTree
):
	TestRunner.Test(runwith=InGame)(_func)

del _m, _func


#TODO: Add tests. Will probably require exception field in IPC protocol to use.

#Basic IPC protocol specs and Pythonic operators.

#No error in any examples.

#ScriptingScope properties correctly set and nullified by different screens.

#Token reuse, once that's implemented.

#Probably don't bother with DOCTEST, or anything. Just use assert statements where needed, print out any errors, and check in the build tests that there's no exceptions (by flag, or by printout value).


