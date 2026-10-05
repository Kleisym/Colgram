package main

import "os"

func osEnv(k string) string { return os.Getenv(k) }

