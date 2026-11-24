"""Data-only stand-ins for the exteraGram settings widgets.

``SimpleSettingFactory`` is deliberately unusable: the slider factory in the
SDK builds raw Android views, which DeltaGram does not render. Plugins catch
the failure and fall back to their pure-Python ``Selector``/``Switch`` items,
which the Plugins screen renders natively.
"""


class _Widget:
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)

    def __repr__(self):
        return "%s(%s)" % (type(self).__name__, self.__dict__)


class Header(_Widget):
    pass


class Switch(_Widget):
    pass


class Selector(_Widget):
    pass


class Divider(_Widget):
    pass


class Text(_Widget):
    pass


class Input(_Widget):
    pass


class Custom(_Widget):
    pass


class EditText(_Widget):
    pass


class SimpleSettingFactory:
    def __init__(self, *args, **kwargs):
        raise NotImplementedError("custom settings rows are not rendered yet")
