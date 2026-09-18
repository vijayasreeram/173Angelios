@echo off
title iTANTRA - AI4Bharat Models Downloader
color 0A
cd /d "c:\Users\CMRMuthuthiyagarajan\Downloads\PS-2(SIH)"
echo ===============================================================================
echo               iTANTRA - AI4BHARAT PRETRAINED MODEL DOWNLOADER
echo ===============================================================================
echo.
python backend\interactive_download.py
echo.
echo ===============================================================================
echo Testing speech translation pipeline on your NVIDIA RTX 4050 GPU...
echo ===============================================================================
python backend\pipeline.py
echo.
echo All tasks finished.
pause
