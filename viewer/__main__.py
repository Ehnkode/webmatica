import sys

from .firstrun import prepare


def main():
    argv = prepare(sys.argv[1:])
    from .server import main as run      # erst jetzt: braucht die Assets schon beim Import
    run(argv)


if __name__ == "__main__":
    main()
