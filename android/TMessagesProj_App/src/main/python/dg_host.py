"""DeltaGram host for exteraGram-style Python plugins.

Loaded through Chaquopy. The Java side calls ``load_plugin(path, id)`` and gets
back an empty string on success or a traceback on failure. Hooks installed by
``hook_all_methods`` are executed by Pine through :mod:`dg_hooks`.
"""
import importlib.util
import json
import os
import sys
import traceback

import dg_hooks

# exteraGram's jlong/jint/jfloat are Chaquopy primitive wrappers. Plugins use
# them as plain numbers when they assign into hook arguments, and the Pine
# bridge re-boxes values according to the real parameter types.
try:
    import java as _java

    _java.jboolean = lambda value, truncate=False: bool(value)
    _java.jbyte = lambda value, truncate=False: int(value)
    _java.jshort = lambda value, truncate=False: int(value)
    _java.jint = lambda value, truncate=False: int(value)
    _java.jlong = lambda value, truncate=False: int(value)
    _java.jfloat = lambda value, truncate=False: float(value)
    _java.jdouble = lambda value, truncate=False: float(value)
    _java.jchar = lambda value: str(value)[0] if str(value) else "\0"
except Exception:
    pass

_loaded = {}


def _entry_class(module, module_name):
    from base_plugin import BasePlugin

    # Plugins overwrite __name__ with their display name (__name__ = "Animation
    # Speed"), so classes defined in the file carry that as __module__. Accept
    # both the real module name and the module's current __name__.
    names = {module_name, getattr(module, "__name__", None)}
    for value in vars(module).values():
        if not isinstance(value, type) or value is BasePlugin:
            continue
        if not issubclass(value, BasePlugin):
            continue
        if getattr(value, "__module__", None) in names:
            return value
    return None


def load_plugin(path, plugin_id):
    try:
        module_name = "dgplugin_" + "".join(
            ch if ch.isalnum() else "_" for ch in plugin_id)
        # importlib cannot infer a loader for the .plugin extension, so build
        # the spec explicitly from a source loader.
        from importlib.machinery import SourceFileLoader
        loader = SourceFileLoader(module_name, path)
        spec = importlib.util.spec_from_loader(module_name, loader)
        if spec is None or spec.loader is None:
            return "cannot load " + str(path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)

        cls = _entry_class(module, module_name)
        if cls is None:
            return "no BasePlugin subclass in " + os.path.basename(path)

        plugin_dir = os.path.join(os.path.dirname(path), plugin_id)
        try:
            os.makedirs(plugin_dir, exist_ok=True)
        except Exception:
            pass

        cls._dg_id = plugin_id
        cls._dg_dir = plugin_dir
        instance = cls()
        _loaded[plugin_id] = instance
        instance.on_plugin_load()
        return ""
    except Exception:
        _loaded.pop(plugin_id, None)
        dg_hooks.unhook_owner(plugin_id)
        return traceback.format_exc(limit=12)


def unload_plugin(plugin_id):
    instance = _loaded.pop(plugin_id, None)
    dg_hooks.unhook_owner(plugin_id)
    if instance is None:
        return ""
    try:
        instance.on_plugin_unload()
        return ""
    except Exception:
        return traceback.format_exc(limit=12)


def plugin_info(plugin_id):
    """Metadata of a loaded plugin, as a JSON string."""
    instance = _loaded.get(plugin_id)
    if instance is None:
        return "{}"
    return json.dumps({
        "id": plugin_id,
        "class": type(instance).__name__,
        "settings": len(instance.create_settings() or []),
    })


# ------------------------------------------------------------------ settings


def _widget_dict(item, index, plugin):
    data = {"type": type(item).__name__, "index": index}
    for key, value in vars(item).items():
        if key in ("on_change", "on_click", "factory"):
            continue
        if isinstance(value, (str, int, float, bool)) or value is None:
            data[key] = value
        elif isinstance(value, (list, tuple)):
            data[key] = [str(v) for v in value]
    setting_key = data.get("key")
    if setting_key:
        try:
            data["value"] = plugin.get_setting(setting_key, data.get("default"))
        except Exception:
            data["value"] = data.get("default")
    return data


def get_settings(plugin_id):
    """Settings of a loaded plugin, rendered by the Plugins screen."""
    instance = _loaded.get(plugin_id)
    if instance is None:
        return json.dumps({"error": "плагин не загружен"})
    try:
        items = instance.create_settings() or []
    except Exception:
        return json.dumps({"error": traceback.format_exc(limit=10)})
    result = []
    for index, item in enumerate(items):
        try:
            result.append(_widget_dict(item, index, instance))
        except Exception:
            continue
    return json.dumps(result, ensure_ascii=False)


def set_setting(plugin_id, key, value_json):
    instance = _loaded.get(plugin_id)
    if instance is None:
        return "плагин не загружен"
    try:
        value = json.loads(value_json)
    except Exception:
        value = value_json
    try:
        instance.set_setting(key, value)
    except Exception:
        return traceback.format_exc(limit=8)
    try:
        for item in instance.create_settings() or []:
            if getattr(item, "key", None) == key:
                callback = getattr(item, "on_change", None)
                if callback is not None:
                    callback()
                break
    except Exception:
        return traceback.format_exc(limit=8)
    return ""


def run_action(plugin_id, index):
    instance = _loaded.get(plugin_id)
    if instance is None:
        return "плагин не загружен"
    try:
        items = instance.create_settings() or []
        if index < 0 or index >= len(items):
            return "нет такой настройки"
        callback = getattr(items[index], "on_click", None)
        if callback is not None:
            callback()
        return ""
    except Exception:
        return traceback.format_exc(limit=8)


def hook_errors(plugin_id):
    return json.dumps(dg_hooks.last_errors(), ensure_ascii=False)
