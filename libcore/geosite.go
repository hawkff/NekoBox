package libcore

import (
	"fmt"
	"path/filepath"
	"slices"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	geositeReader *geosites.Reader
	codes         []string
}

func (g *geosite) Open(path string) error {
	geositeReader, codes, err := geosites.Open(path)
	g.geositeReader = geositeReader
	g.codes = codes
	return err
}

// Rules reads the list named code. A name the database lacks falls back to category-<code>:
// older default rules ask for ir and ru, which most rule assets only have as category-ir and
// category-ru, and a missing list stops the core from starting.
func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	if !slices.Contains(g.codes, code) && slices.Contains(g.codes, "category-"+code) {
		code = "category-" + code
	}
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		g := new(geosite)
		if err := g.Open(filepath.Join(externalAssetsPath, "geosite.db")); err != nil {
			return nil, err
		}
		return g.Rules(name)
	}
}
