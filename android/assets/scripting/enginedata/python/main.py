"""
There are basically two types of Python objects in this API:

	*	Wrappers.
	*	Tokens.

---

A **wrapper** is an object that stores a list of attribute names, keys, and function call parameters corresponding to a path in the Kotlin/JVM namespace. E.G.: `"civInfo.civilizations[0].population.setPopulation"` is a string representation of a wrapped path that begins with two attribute accesses, followed by one array index, and two more attribute names.

A wrapper object does not store any more values than that. When it is evaluated, it uses a simple IPC protocol to request an up-to-date real value from the game's Kotlin code.

Because the wrapper class implements many Magic Methods, many programming idioms common in Python are possible with them.

Accessing an attribute on a wrapper object returns a new wrapper object that has an additional name set to "Property" at the end of its path list. Performing an array or dictionary index returns a new wrapper with an additional "Key" element in its path list.

Calling a wrapper object as a function or method also creates a new path list with an extra "Call" element at the end. But in this case, the new path list is immediately sent to Kotlin/the JVM instead of being used in a new wrapper object, and the returned value is the result from the requested function call in the Kotlin/JVM namespace.

Likewise, assigning to an attribute or an index/key on a wrapper object sends an IPC request to assign to the Kotlin/JVM object at its path, instead of modifying the wrapper object.

When a Kotlin/JVM object implements a property for size or keys, the Python wrapper for it can also be iterated like a `tuple` or a `dict`, such as in `for` loops anlist comprehensions.

---

A **token** is a string that has been generated by `ScriptingObjectIndex.kt` to represent a Kotlin object.

When a path requested by a script resolves to immutable primative like a number or boolean, or something that is otherwise practical to serialize, then the return is usually a real object.

However, if the value requested is an instance of a complicated Kotlin/JVM class, then the IPC protocol instead creates a unique string to identify it.

The original object is stored in the JVM in a mapping as a weak reference. The string doesn't have (m)any special properties as a Python object. But if it is sent back to Kotlin/the JVM at any point, then it will be parsed and substitued with the original object (provided the original object still exists).

This is meant to allow Kotlin/JVM objects to be, E.G., used as function arguments and mapping keys from scripts.

---

Some of this may not yet be fully implemented.

The Python-specific behaviour is also not standardized, and doesn't have to be copied exactly in any other languages. Some other design may be more suited for ECMAScript, Lua, and other possible backends.

"""

try:
	import sys, json

	stdout = sys.stdout
	
	import unciv
	
	
	foreignActionSender = unciv.ipc.ForeignActionSender()
	
	foreignScope = {n: unciv.wrapping.ForeignObject(n, foreignrequester=foreignActionSender.GetForeignActionResponse) for n in ('civInfo', 'gameInfo', 'uncivGame', 'worldScreen', 'isInGame')}
	
	class fsdebug:
		pass
	
	fsdebug = fsdebug()
	fsdebug.__dict__ = foreignScope
	
	foreignAutocompleter = unciv.autocompletion.AutocompleteManager(foreignScope)
	
	class ForeignActionReplReceiver(unciv.ipc.ForeignActionReceiver):
		@unciv.ipc.receiverMethod('motd', 'motd_response')
		def EvalForeignMotd(self, packet):
			return f"""

Welcome to the CPython Unciv CLI. Currently, this backend relies on launching the system `python3` command.

sys.implementation == {str(sys.implementation)}

"""
		@unciv.ipc.receiverMethod('autocomplete', 'autocomplete_response')
		def EvalForeignAutocomplete(self, packet):
			assert 'PassMic' in packet.flags, f"Expected 'PassMic' in packet flags: {packet}"
			res = foreignAutocompleter.GetAutocomplete(packet.data["command"])
			foreignActionSender.SendForeignAction({'action':None, 'identifier': None, 'data':None, 'flags':('PassMic',)})
			return res
		@unciv.ipc.receiverMethod('exec', 'exec_response')
		def EvalForeignExec(self, packet):
			line = packet.data
			assert 'PassMic' in packet.flags, f"Expected 'PassMic' in packet flags: {packet}"
			with unciv.ipc.FakeStdout() as fakeout:
				print(f">>> {str(line)}")
				try:
					try:
						code = compile(line, 'STDIN', 'eval')
					except SyntaxError:
						exec(compile(line, 'STDIN', 'exec'), self.scope, self.scope)
					else:
						print(eval(code, self.scope, self.scope))
				except Exception as e:
					return unciv.utils.formatException(e)
				else:
					return fakeout.getvalue()
				finally:
					foreignActionSender.SendForeignAction({'action':None, 'identifier': None, 'data':None, 'flags':('PassMic',)})
				
		@unciv.ipc.receiverMethod('terminate', 'terminate_response')
		def EvalForeignTerminate(self, packet):
			return None

	foreignActionReceiver = ForeignActionReplReceiver(scope=foreignScope)
	
	foreignActionReceiver.ForeignREPL()
	# Disable this to run manually with `python3 -i main.py` for debug.
	
except Exception as e:
	print(f"Fatal error in Python interepreter: {unciv.utils.formatException(e)}", file=stdout, flush=True)
