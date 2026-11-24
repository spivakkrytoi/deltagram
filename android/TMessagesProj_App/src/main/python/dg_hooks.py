"""Hook registry shared by the Python SDK and the Java Pine bridge.

Java calls :func:`dispatch_before` / :func:`dispatch_after` for every hooked
call, so the callbacks defined by plugins run inside the app process.
"""
import traceback

from java import jclass

_hooks = {}
_owners = {}
_next_id = [1]
_last_errors = []


def _bridge():
    return jclass("org.telegram.ui.Plugins.HookBridge")


class MethodHookParam:
    """exteraGram-compatible view of a hooked call."""

    def __init__(self, raw):
        self._raw = raw
        self.method = raw.method
        self.thisObject = raw.thisObject
        self.args = [raw.args[i] for i in range(len(raw.args))]
        self.result = raw.result
        self._result_set = False

    def setResult(self, value):
        self.result = value
        self._result_set = True

    def getResult(self):
        return self.result

    def _write_back(self):
        raw = self._raw
        raw.thisObject = self.thisObject
        count = min(len(self.args), len(raw.args))
        for i in range(count):
            raw.args[i] = self.args[i]
        if self._result_set:
            raw.result = self.result
            raw.resultSet = True


def _register(hook, owner):
    hook_id = _next_id[0]
    _next_id[0] += 1
    _hooks[hook_id] = hook
    _owners[hook_id] = owner
    return hook_id


def _error(message):
    _last_errors.append(message)
    del _last_errors[:-20]
    try:
        print("[dg-hooks] " + message)
    except Exception:
        pass


def hook_all_methods(cls, method_name, hook, owner=None):
    hook_id = _register(hook, owner)
    try:
        error = str(_bridge().hookAllMethods(str(cls.getName()), method_name, hook_id))
    except Exception:
        _hooks.pop(hook_id, None)
        _owners.pop(hook_id, None)
        raise
    if error:
        _hooks.pop(hook_id, None)
        _owners.pop(hook_id, None)
        raise RuntimeError(error)
    return [hook_id]


def hook_all_constructors(cls, hook, owner=None):
    hook_id = _register(hook, owner)
    try:
        error = str(_bridge().hookAllConstructors(str(cls.getName()), hook_id))
    except Exception:
        _hooks.pop(hook_id, None)
        _owners.pop(hook_id, None)
        raise
    if error:
        _hooks.pop(hook_id, None)
        _owners.pop(hook_id, None)
        raise RuntimeError(error)
    return [hook_id]


def hook_method(method, hook, owner=None):
    hook_id = _register(hook, owner)
    try:
        error = str(_bridge().hookMethod(method, hook_id))
    except Exception:
        _hooks.pop(hook_id, None)
        _owners.pop(hook_id, None)
        raise
    if error:
        _hooks.pop(hook_id, None)
        _owners.pop(hook_id, None)
        raise RuntimeError(error)
    return [hook_id]


def unhook_owner(owner):
    for hook_id, hook_owner in list(_owners.items()):
        if hook_owner != owner:
            continue
        _owners.pop(hook_id, None)
        _hooks.pop(hook_id, None)
        try:
            _bridge().unhook(hook_id)
        except Exception:
            _error(traceback.format_exc(limit=4))


def unhook_all():
    for hook_id in list(_hooks.keys()):
        _hooks.pop(hook_id, None)
        _owners.pop(hook_id, None)
    try:
        _bridge().unhookAll()
    except Exception:
        _error(traceback.format_exc(limit=4))


def dispatch_before(hook_id, raw):
    hook = _hooks.get(hook_id)
    if hook is None:
        return
    param = MethodHookParam(raw)
    try:
        hook.before_hooked_method(param)
    except Exception:
        _error("before hook " + str(hook_id) + ": " + traceback.format_exc(limit=8))
    try:
        param._write_back()
    except Exception:
        _error("write-back " + str(hook_id) + ": " + traceback.format_exc(limit=8))


def dispatch_after(hook_id, raw):
    hook = _hooks.get(hook_id)
    if hook is None:
        return
    param = MethodHookParam(raw)
    try:
        hook.after_hooked_method(param)
    except Exception:
        _error("after hook " + str(hook_id) + ": " + traceback.format_exc(limit=8))
    try:
        param._write_back()
    except Exception:
        _error("write-back " + str(hook_id) + ": " + traceback.format_exc(limit=8))


def last_errors():
    return list(_last_errors)
