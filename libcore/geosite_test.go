package libcore

import (
	"bytes"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	geosites "github.com/sagernet/sing-box/common/geosite"
)

func TestGeositeFallsBackToTheCategoryList(t *testing.T) {
	// Like the bundled database, this one has category-ru only; it also has ir next to category-ir,
	// as Chocolate4U's rules do.
	var buffer bytes.Buffer
	err := geosites.Write(&buffer, map[string][]geosites.Item{
		"category-ru": {{Type: geosites.RuleTypeDomainSuffix, Value: "ru"}},
		"category-ir": {{Type: geosites.RuleTypeDomainSuffix, Value: "ir"}},
		"ir":          {{Type: geosites.RuleTypeDomain, Value: "example.ir"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), geositeDat)
	if err := os.WriteFile(path, buffer.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	g := new(geosite)
	if err := g.Open(path); err != nil {
		t.Fatal(err)
	}
	rules := func(code string) any {
		result, err := g.Rules(code)
		if err != nil {
			t.Fatal(code, err)
		}
		return result
	}
	if !reflect.DeepEqual(rules("ru"), rules("category-ru")) {
		t.Fatal("ru does not read category-ru")
	}
	if reflect.DeepEqual(rules("ir"), rules("category-ir")) {
		t.Fatal("ir is replaced although the database has it")
	}
	if _, err := g.Rules("no-such-list"); err == nil {
		t.Fatal("a missing list was read")
	}
}
