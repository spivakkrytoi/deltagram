"""Reflection helpers used by exteraGram plugins."""
from java import jclass


def find_class(name):
    """Returns a Java class proxy, or None when the class is not in the app."""
    try:
        return jclass(name)
    except Exception:
        return None


def find_method(cls, name, *args):
    try:
        return getattr(cls, name)
    except Exception:
        return None


def get_field(obj, name):
    try:
        return getattr(obj, name)
    except Exception:
        return None


def set_field(obj, name, value):
    try:
        setattr(obj, name, value)
        return True
    except Exception:
        return False
