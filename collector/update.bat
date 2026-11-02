@echo off
rem Brings the TV's own performance log to this PC and rebuilds the public datasets (for Task Scheduler).
rem The TV's IP address is in datasets\private\tv_ip.txt (one line), kept off GitHub.
cd /d "%~dp0.."
set /p TV=<datasets\private\tv_ip.txt
"%USERPROFILE%\anaconda3\python.exe" collector\pull_tv_log.py %TV% >> datasets\private\update.log 2>&1
"%USERPROFILE%\anaconda3\python.exe" collector\build_datasets.py >> datasets\private\update.log 2>&1
