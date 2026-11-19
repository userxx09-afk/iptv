//go:build windows

package main

// Windows installer, built into the program itself: double-click
// TapperAlexaRelaySetup.exe and it asks for admin rights, copies itself to
// Program Files, starts at every boot as a background task (SYSTEM, restarts
// if it stops), opens the firewall on the home network, adds itself to
// Settings > Apps for uninstalling, and opens the setup page.

import (
	"bufio"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const (
	taskName    = "Tapper Alexa Relay"
	exeName     = "tapper-alexa-relay.exe"
	uninstallKy = `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\TapperAlexaRelay`
)

func installDir() string {
	pf := os.Getenv("ProgramFiles")
	if pf == "" {
		pf = `C:\Program Files`
	}
	return filepath.Join(pf, "Tapper Alexa Relay")
}

func defaultDataDir() string {
	pd := os.Getenv("ProgramData")
	if pd == "" {
		pd = `C:\ProgramData`
	}
	return filepath.Join(pd, "Tapper Alexa Relay")
}

// Double-clicked: install, unless this is the installed copy (then serve).
func defaultCommand() string {
	self, err := os.Executable()
	if err == nil && strings.EqualFold(filepath.Dir(self), installDir()) {
		return "serve"
	}
	return "install"
}

func isAdmin() bool {
	f, err := os.Open(`\\.\PHYSICALDRIVE0`)
	if err != nil {
		return false
	}
	f.Close()
	return true
}

func pauseIfWindows() {
	fmt.Print("\nPress Enter to close this window.")
	_, _ = bufio.NewReader(os.Stdin).ReadString('\n')
}

func powershell(script string) (string, error) {
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return string(out), fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return string(out), nil
}

func psQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

// relaunchElevated runs this exe again with the same arguments, as admin (UAC prompt).
func relaunchElevated(args ...string) error {
	self, err := os.Executable()
	if err != nil {
		return err
	}
	quoted := []string{}
	for _, a := range args {
		quoted = append(quoted, psQuote(a))
	}
	argList := ""
	if len(quoted) > 0 {
		argList = " -ArgumentList " + strings.Join(quoted, ",")
	}
	_, err = powershell("Start-Process -FilePath " + psQuote(self) + argList + " -Verb RunAs")
	if err != nil {
		return fmt.Errorf("Windows didn't give admin rights (%v)", err)
	}
	return nil
}

func install(dataDir string, port int) error {
	if !isAdmin() {
		fmt.Println("Tapper Alexa relay setup needs admin rights. Windows will ask...")
		return relaunchElevated("install", "--data", dataDir, "--port", fmt.Sprint(port))
	}
	fmt.Printf("Installing Tapper Alexa relay %s\n\n", version)
	self, err := os.Executable()
	if err != nil {
		return err
	}
	dir := installDir()
	target := filepath.Join(dir, exeName)

	// An older copy may be running: stop it so the file can be replaced.
	_, _ = powershell("Stop-ScheduledTask -TaskName " + psQuote(taskName) + " -ErrorAction SilentlyContinue")
	_ = exec.Command("taskkill.exe", "/F", "/IM", exeName).Run()
	time.Sleep(time.Second)

	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if err := copyFile(self, target); err != nil {
		return fmt.Errorf("copying to %s: %w", dir, err)
	}
	fmt.Println("  Copied to", dir)

	st, err := openStore(dataDir) // makes config.json with the setup PIN and relay key
	if err != nil {
		return err
	}
	// Settings hold Amazon sign-in tokens: only SYSTEM and Administrators may read them.
	_ = exec.Command("icacls.exe", dataDir, "/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F").Run()
	fmt.Println("  Settings in", dataDir)

	script := fmt.Sprintf(`
$a = New-ScheduledTaskAction -Execute %s -Argument %s
$t = New-ScheduledTaskTrigger -AtStartup
$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
$p = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName %s -Description 'Alexa voice control for Tapper IPTV and GarysTV' -Action $a -Trigger $t -Settings $s -Principal $p -Force | Out-Null
Start-ScheduledTask -TaskName %s
`, psQuote(target), psQuote(fmt.Sprintf(`serve --data "%s" --port %d`, dataDir, port)), psQuote(taskName), psQuote(taskName))
	if _, err := powershell(script); err != nil {
		return fmt.Errorf("creating the startup task: %w", err)
	}
	fmt.Println("  Starts with Windows (Task Scheduler: \"" + taskName + "\")")

	_, _ = powershell(fmt.Sprintf(`Get-NetFirewallRule -DisplayName %s -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName %s -Direction Inbound -Protocol TCP -LocalPort %d -Profile Private,Domain -Action Allow | Out-Null`, psQuote(taskName), psQuote(taskName), port))
	fmt.Printf("  Firewall: port %d open on the home network only\n", port)

	for _, kv := range [][2]string{
		{"DisplayName", "Tapper Alexa Relay"}, {"DisplayVersion", version}, {"Publisher", "Tapper"},
		{"DisplayIcon", target}, {"InstallLocation", dir},
		{"UninstallString", `"` + target + `" uninstall`},
	} {
		_ = exec.Command("reg.exe", "add", uninstallKy, "/v", kv[0], "/t", "REG_SZ", "/d", kv[1], "/f").Run()
	}
	_ = exec.Command("reg.exe", "add", uninstallKy, "/v", "NoModify", "/t", "REG_DWORD", "/d", "1", "/f").Run()

	url := fmt.Sprintf("http://localhost:%d/setup", port)
	ok := false
	for i := 0; i < 20 && !ok; i++ {
		time.Sleep(500 * time.Millisecond)
		if r, err := http.Get(fmt.Sprintf("http://localhost:%d/health", port)); err == nil {
			r.Body.Close()
			ok = r.StatusCode == 200
		}
	}
	pin := st.setupPIN()
	fmt.Println()
	if ok {
		fmt.Println("Tapper Alexa relay is running.")
	} else {
		fmt.Printf("Installed, but it isn't answering on port %d yet. If another program uses that port, see %s\\relay.log\n", port, dataDir)
	}
	fmt.Printf("\nSetup page: %s\nSetup PIN:  %s\n", url, pin)
	_ = exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", url+"#pin="+pin).Start()
	pauseIfWindows()
	return nil
}

func uninstall() error {
	if !isAdmin() {
		return relaunchElevated("uninstall")
	}
	_, _ = powershell("Stop-ScheduledTask -TaskName " + psQuote(taskName) + " -ErrorAction SilentlyContinue; Unregister-ScheduledTask -TaskName " + psQuote(taskName) + " -Confirm:$false -ErrorAction SilentlyContinue")
	_, _ = powershell("Get-NetFirewallRule -DisplayName " + psQuote(taskName) + " -ErrorAction SilentlyContinue | Remove-NetFirewallRule")
	_ = exec.Command("reg.exe", "delete", uninstallKy, "/f").Run()
	// Kill other copies (not this one), then remove the folder after this exits.
	_ = exec.Command("taskkill.exe", "/F", "/FI", fmt.Sprintf("PID ne %d", os.Getpid()), "/IM", exeName).Run()
	dir := installDir()
	_ = exec.Command("cmd.exe", "/C", "ping -n 3 127.0.0.1 >nul & rmdir /S /Q \""+dir+"\"").Start()
	fmt.Println("Tapper Alexa relay is removed. Its settings stay in", defaultDataDir(), "(delete that folder to forget the Amazon sign-in and linked TVs).")
	fmt.Println("The Alexa skill stays on your Amazon account; delete it at developer.amazon.com/alexa/console/ask if you don't want it.")
	pauseIfWindows()
	return nil
}

func copyFile(src, dst string) error {
	if strings.EqualFold(src, dst) {
		return nil
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".new"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	_ = os.Remove(dst)
	return os.Rename(tmp, dst)
}
