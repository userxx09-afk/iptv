package main

// The skill the setup page creates: its manifest and en-US interaction model.

func skillManifest(cfg Config) map[string]any {
	inv := cfg.Invocation
	return map[string]any{
		"manifest": map[string]any{
			"manifestVersion": "1.0",
			"publishingInformation": map[string]any{
				"locales": map[string]any{
					"en-US": map[string]any{
						"name":        cfg.SkillName,
						"summary":     "Control Tapper IPTV and Gary's TV on your TVs by voice.",
						"description": "Play a channel by name or number, change channel, pause, resume, stop, open the guide or your recordings in Tapper IPTV or Gary's TV, on a TV you linked.",
						"examplePhrases": []string{
							"Alexa, ask " + inv + " to play ESPN",
							"Alexa, ask " + inv + " to go to channel 204",
							"Alexa, ask " + inv + " for the next channel",
						},
						"keywords": []string{"iptv", "tv", "fire tv", "dvr"},
					},
				},
				"isAvailableWorldwide":  false,
				"distributionCountries": []string{"US"},
				"testingInstructions":   "Private skill for the owner's own TVs. Never published.",
				"category":              "VIDEO",
			},
			"apis": map[string]any{
				"custom": map[string]any{
					"endpoint": map[string]any{
						"uri":                cfg.PublicURL + "/alexa",
						"sslCertificateType": cfg.CertType,
					},
				},
			},
		},
	}
}

type intent struct {
	Name    string     `json:"name"`
	Slots   []slotDecl `json:"slots,omitempty"`
	Samples []string   `json:"samples"`
}

type slotDecl struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

func interactionModel(cfg Config) map[string]any {
	intents := []intent{
		{Name: "AMAZON.CancelIntent", Samples: []string{}},
		{Name: "AMAZON.HelpIntent", Samples: []string{}},
		{Name: "AMAZON.StopIntent", Samples: []string{}},
		{Name: "AMAZON.NavigateHomeIntent", Samples: []string{}},
		{Name: "AMAZON.FallbackIntent", Samples: []string{}},
		{Name: "AMAZON.PauseIntent", Samples: []string{}},
		{Name: "AMAZON.ResumeIntent", Samples: []string{}},
		{Name: "LinkIntent", Slots: []slotDecl{{"code", "AMAZON.NUMBER"}},
			Samples: []string{"link code {code}", "link {code}", "pair code {code}", "the code is {code}", "{code}"}},
		{Name: "TuneIntent", Slots: []slotDecl{{"channel", "AMAZON.SearchQuery"}},
			Samples: []string{"play {channel}", "put on {channel}", "tune to {channel}", "watch {channel}", "switch to {channel}", "turn on {channel}"}},
		{Name: "ChannelNumberIntent", Slots: []slotDecl{{"number", "AMAZON.NUMBER"}},
			Samples: []string{"channel {number}", "go to channel {number}", "tune to channel {number}", "put on channel {number}", "change to channel {number}"}},
		{Name: "ChannelUpIntent", Samples: []string{"next channel", "channel up", "go up a channel"}},
		{Name: "ChannelDownIntent", Samples: []string{"previous channel", "channel down", "go back a channel", "last channel"}},
		{Name: "StopWatchingIntent", Samples: []string{"stop watching", "stop the tv", "stop playing", "turn it off"}},
		{Name: "GuideIntent", Samples: []string{"open the guide", "show the guide", "guide", "what's on", "show me what's on"}},
		{Name: "RecordingsIntent", Samples: []string{"show my recordings", "open my recordings", "my recordings", "recordings", "open recordings", "show recordings"}},
		{Name: "BackIntent", Samples: []string{"go back", "back"}},
	}
	return map[string]any{
		"interactionModel": map[string]any{
			"languageModel": map[string]any{
				"invocationName": cfg.Invocation,
				"intents":        intents,
				"types":          []any{},
			},
		},
	}
}
