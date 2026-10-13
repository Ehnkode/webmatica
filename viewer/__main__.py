import sys

from .firstrun import prepare


def main():
    argv = prepare(sys.argv[1:])
    from .server import main as run      # only now: needs the assets already at import time
    run(argv)


if __name__ == "__main__":
    main()
