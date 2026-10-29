package libcore

import (
	"os"
	"path/filepath"

	"github.com/ulikunitz/xz"
)

const defaultUnxzFileLimit = 256 * 1024 * 1024

func Unxz(archive string, path string) (err error) {
	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer i.Close()

	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}

	dir, base := filepath.Split(path)
	if dir == "" {
		dir = "."
	}
	o, err := os.CreateTemp(dir, base+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := o.Name()
	defer func() {
		if err != nil {
			_ = os.Remove(tmpPath)
		}
	}()
	defer func() {
		if closeErr := o.Close(); err == nil {
			err = closeErr
		}
		if err == nil {
			err = os.Rename(tmpPath, path)
		}
	}()

	_, err = copyLimited(o, r, defaultUnxzFileLimit)
	return err
}
