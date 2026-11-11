package libcore

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Cores side by side, as URL tests run them, open their own cache files: on the one sing-box picks by
// default, the second waited for the first's lock and failed with "initialize cache-file: timeout".
func TestCoresSideBySideOpenTheirOwnCacheFiles(t *testing.T) {
	t.Chdir(t.TempDir())
	started := time.Now()
	var instances []*BoxInstance
	for range 3 {
		instance, err := NewSingBoxInstance(`{"log":{"level":"panic"},"outbounds":[{"type":"direct","tag":"direct"}]}`, nil)
		if err != nil {
			t.Fatal(err)
		}
		// Closed again below; Close is safe to repeat, and this covers a test that stops early.
		t.Cleanup(func() { instance.Close() })
		instances = append(instances, instance)
		if err := instance.PreStart(); err != nil {
			t.Fatal(err)
		}
	}
	if waited := time.Since(started); waited > 5*time.Second {
		t.Fatalf("the cores waited %v for each other", waited)
	}
	if dirs, _ := filepath.Glob("core-cache-*"); len(dirs) != 3 {
		t.Fatalf("cache directories %v, want one a core", dirs)
	}
	for _, instance := range instances {
		if err := instance.Close(); err != nil {
			t.Fatal(err)
		}
	}
	if dirs, _ := filepath.Glob("core-cache-*"); len(dirs) != 0 {
		t.Fatalf("cache directories left after closing: %v", dirs)
	}
}

// A config that keeps a cache file of its own, as the running service's and full sing-box configs do, keeps
// it where sing-box puts it: at the path it names, or at cache.db when it only enables one.
func TestACoreKeepsTheCacheFileItsConfigKeeps(t *testing.T) {
	for _, c := range []struct{ cacheFile, file string }{
		{`{"enabled":true,"path":"named.db"}`, "named.db"},
		{`{"enabled":true}`, "cache.db"},
	} {
		t.Run(c.file, func(t *testing.T) {
			t.Chdir(t.TempDir())
			instance, err := NewSingBoxInstance(`{"log":{"level":"panic"},"experimental":{"cache_file":`+c.cacheFile+`},
				"outbounds":[{"type":"direct","tag":"direct"}]}`, nil)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { instance.Close() })
			if err := instance.PreStart(); err != nil {
				t.Fatal(err)
			}
			if err := instance.Close(); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(c.file); err != nil {
				t.Fatalf("the config's cache file: %v", err)
			}
			if dirs, _ := filepath.Glob("core-cache-*"); len(dirs) != 0 {
				t.Fatalf("a core with its own cache file made %v", dirs)
			}
		})
	}
}

// A selector takes up the outbound it was last switched to from the cache file. A start that is for
// another one, as the app's are for the selected profile, has that forgotten before the core starts.
func TestACoreStartsWithItsConfigsOutboundOnceTheKeptOneIsForgotten(t *testing.T) {
	t.Chdir(t.TempDir())
	started := func(forget bool) *BoxInstance {
		instance, err := NewSingBoxInstance(`{"log":{"level":"panic"},"experimental":{"cache_file":{"enabled":true}},
			"outbounds":[{"type":"selector","tag":"proxy","outbounds":["first","second"],"default":"first"},
			{"type":"direct","tag":"first"},{"type":"direct","tag":"second"}]}`, nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { instance.Close() })
		if forget {
			if err := instance.ForgetSelected(); err != nil {
				t.Fatal(err)
			}
		}
		if err := instance.PreStart(); err != nil {
			t.Fatal(err)
		}
		return instance
	}
	switched := started(false)
	if !switched.SelectOutbound("second") {
		t.Fatal("the selector did not take the switch")
	}
	switched.Close()
	kept := started(false)
	if selected := kept.SelectedOutbound(); selected != "second" {
		t.Fatalf("the core started with %q, where it keeps what it was switched to", selected)
	}
	kept.Close()
	// Forgetting is not a switch: twice in a row it still leaves the config's outbound.
	for range 2 {
		forgotten := started(true)
		if selected := forgotten.SelectedOutbound(); selected != "first" {
			t.Fatalf("the core started with %q after the kept outbound was forgotten", selected)
		}
		forgotten.Close()
	}
}
