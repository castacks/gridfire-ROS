@echo off

call lein deps
call lein git-deps
call lein install
