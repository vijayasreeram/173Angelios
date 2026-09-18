@echo off
title iTANTRA - SIH-PS2-MODEL IndicTrans2 Edge Server
cd /d " %~dp0SIH-PS2-MODEL-main\
echo ======================================================================
echo iTANTRA: Tactical Mesh Walkie-Talkie Base Station AI Server
echo Loading SIH-PS2-MODEL (IndicTrans2) on GPU/CUDA...
echo ======================================================================
python app.py
pause
